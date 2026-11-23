// The request handling for `extract`, with its dependencies passed in so tests can fake them.
// index.ts wires in the real ones. See DESIGN.md -> Edge Function.

import {
  BadOutput,
  EFFORT,
  type Extraction,
  MAX_TOKENS,
  MODEL,
  type Mode,
  normalize,
  schemaFor,
  SYSTEM_PROMPT,
  userInstruction,
} from "./schema.ts";

/** The part of the Anthropic client the handler uses. */
export interface ModelMessage {
  stop_reason: string | null;
  content: Array<{ type: string; text?: string }>;
  usage?: { input_tokens: number; output_tokens: number };
}

export interface Deps {
  supabaseUrl: string;
  anonKey: string;
  /** Null when `ANTHROPIC_API_KEY` is not set. */
  messages: { create(params: Record<string, unknown>): Promise<ModelMessage> } | null;
  fetch: typeof fetch;
  now: () => number;
}

/** The bucket's own limit (supabase/migrations/0002_storage.sql); the model accepts up to the same. */
export const MAX_IMAGE_BYTES = 5 * 1024 * 1024;
export const MAX_BODY_CHARS = 8_000;
/** One notification or SMS (RULES 17). */
export const MAX_TEXT_CHARS = 2_000;

const UUID = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";
/** `{owner_id}/{transaction_id}.jpg`, the only shape the phone uploads (DESIGN.md -> Visibility). */
const PHOTO_PATH = new RegExp(`^(${UUID})/${UUID}\\.jpg$`, "i");
const SOURCE = /^[a-z0-9 ._-]{1,40}$/i;
const BEARER = /^Bearer ([A-Za-z0-9._~+/-]+=*)$/;
const UPSTREAM_TIMEOUT_MS = 15_000;

export function json(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });
}

const fail = (status: number, error: string, message: string) => json(status, { error, message });

type Request_ =
  | { mode: "image"; path: string }
  | { mode: "text"; text: string; source?: string };

/** Checks the request body (RULES 18). Returns the request, or the error response to send. */
export function parseBody(raw: string): Request_ | Response {
  if (raw.length > MAX_BODY_CHARS) return fail(413, "body_too_large", "The request is too large.");
  let body: unknown;
  try {
    body = JSON.parse(raw);
  } catch {
    return fail(400, "bad_request", "The body must be JSON.");
  }
  if (typeof body !== "object" || body === null || Array.isArray(body)) {
    return fail(400, "bad_request", "The body must be a JSON object.");
  }
  const b = body as Record<string, unknown>;
  if (b.mode === "image") {
    if (typeof b.path !== "string" || !PHOTO_PATH.test(b.path)) {
      return fail(400, "bad_request", "`path` must look like <owner id>/<transaction id>.jpg.");
    }
    return { mode: "image", path: b.path };
  }
  if (b.mode === "text") {
    if (typeof b.text !== "string" || b.text.trim() === "" || b.text.length > MAX_TEXT_CHARS) {
      return fail(400, "bad_request", `\`text\` must be 1 to ${MAX_TEXT_CHARS} characters.`);
    }
    if (b.source !== undefined && (typeof b.source !== "string" || !SOURCE.test(b.source))) {
      return fail(400, "bad_request", "`source` must be a short name such as gcash.");
    }
    return { mode: "text", text: b.text, source: b.source as string | undefined };
  }
  return fail(400, "bad_request", "`mode` must be \"image\" or \"text\".");
}

/** Asks Supabase Auth who the token belongs to. This also rejects the anon key, which has no user. */
async function authenticate(req: Request, deps: Deps): Promise<{ jwt: string; userId: string } | Response> {
  const match = BEARER.exec(req.headers.get("Authorization") ?? "");
  if (!match) return fail(401, "unauthorized", "Sign in first.");
  const jwt = match[1];
  let res: Response;
  try {
    res = await deps.fetch(`${deps.supabaseUrl}/auth/v1/user`, {
      headers: { apikey: deps.anonKey, Authorization: `Bearer ${jwt}` },
      signal: AbortSignal.timeout(UPSTREAM_TIMEOUT_MS),
    });
  } catch {
    return fail(502, "auth_unavailable", "Could not check your sign-in. Try again.");
  }
  if (res.status >= 400 && res.status < 500) {
    await res.body?.cancel();
    return fail(401, "unauthorized", "Your sign-in is not valid. Sign in again.");
  }
  if (!res.ok) {
    await res.body?.cancel();
    return fail(502, "auth_unavailable", "Could not check your sign-in. Try again.");
  }
  const user = await res.json().catch(() => null) as { id?: unknown } | null;
  if (typeof user?.id !== "string") return fail(401, "unauthorized", "Your sign-in is not valid. Sign in again.");
  return { jwt, userId: user.id };
}

function toBase64(bytes: Uint8Array): string {
  let binary = "";
  for (let i = 0; i < bytes.length; i += 0x8000) binary += String.fromCharCode(...bytes.subarray(i, i + 0x8000));
  return btoa(binary);
}

/** Downloads the photo with the caller's own token, so Storage's RLS decides whether they may read it. */
async function downloadPhoto(path: string, jwt: string, deps: Deps): Promise<Uint8Array | Response> {
  let res: Response;
  try {
    res = await deps.fetch(`${deps.supabaseUrl}/storage/v1/object/authenticated/receipts/${path}`, {
      headers: { apikey: deps.anonKey, Authorization: `Bearer ${jwt}` },
      signal: AbortSignal.timeout(UPSTREAM_TIMEOUT_MS),
    });
  } catch {
    return fail(502, "storage_unavailable", "Could not fetch the photo. Try again.");
  }
  if (!res.ok) {
    await res.body?.cancel();
    if (res.status === 400 || res.status === 404) return fail(404, "photo_not_found", "The photo has not been uploaded.");
    if (res.status === 401 || res.status === 403) return fail(403, "forbidden", "You cannot read that photo.");
    return fail(502, "storage_unavailable", "Could not fetch the photo. Try again.");
  }
  const bytes = new Uint8Array(await res.arrayBuffer());
  if (bytes.length > MAX_IMAGE_BYTES) return fail(413, "photo_too_large", "The photo is too large.");
  if (bytes.length < 3 || bytes[0] !== 0xff || bytes[1] !== 0xd8 || bytes[2] !== 0xff) {
    return fail(415, "not_a_jpeg", "The file is not a JPEG photo.");
  }
  return bytes;
}

/** Maps a failed model call to a response. 429, overload and network trouble are worth retrying; a refused image is not. */
function modelFailure(e: unknown): Response {
  const status = (e as { status?: unknown })?.status;
  if (status === 429 || status === 529) return fail(503, "model_busy", "The reader is busy. Try again in a minute.");
  if (typeof status === "number" && status >= 500) return fail(502, "model_unavailable", "The reader is not available. Try again.");
  if (status === 400 || status === 413 || status === 422) {
    return fail(422, "unreadable", "The reader could not process that image.");
  }
  if (status === 401 || status === 403) {
    console.error("extract: the Anthropic key was rejected");
    return fail(500, "server_misconfigured", "The receipt reader is not set up correctly.");
  }
  return fail(502, "model_unavailable", "The reader is not available. Try again.");
}

function todayManila(now: number): string {
  return new Date(now + 8 * 3_600_000).toISOString().slice(0, 10);
}

/** Reads the model's reply. Checks `stop_reason` before parsing (DESIGN.md). */
function readReply(mode: Mode, message: ModelMessage): Extraction | Response {
  if (message.stop_reason === "refusal") return fail(422, "refused", "The reader declined to read that.");
  if (message.stop_reason === "max_tokens") return fail(422, "incomplete", "The reader ran out of room. Try a clearer photo.");
  const block = message.content.find((c) => c.type === "text");
  if (!block?.text) return fail(422, "bad_output", "The reader gave no answer.");
  try {
    return normalize(mode, JSON.parse(block.text));
  } catch (e) {
    if (e instanceof BadOutput || e instanceof SyntaxError) return fail(422, "bad_output", "The reader's answer could not be understood.");
    throw e;
  }
}

export async function handle(req: Request, deps: Deps): Promise<Response> {
  if (req.method !== "POST") return fail(405, "method_not_allowed", "Use POST.");
  if (!deps.supabaseUrl || !deps.anonKey || !deps.messages) {
    console.error("extract: SUPABASE_URL, SUPABASE_ANON_KEY or ANTHROPIC_API_KEY is not set");
    return fail(500, "server_misconfigured", "The receipt reader is not set up correctly.");
  }

  const auth = await authenticate(req, deps);
  if (auth instanceof Response) return auth;

  const body = parseBody(await req.text());
  if (body instanceof Response) return body;

  let content: unknown[];
  if (body.mode === "image") {
    if (body.path.split("/")[0].toLowerCase() !== auth.userId.toLowerCase()) {
      return fail(403, "forbidden", "You can only read your own receipts.");
    }
    const photo = await downloadPhoto(body.path, auth.jwt, deps);
    if (photo instanceof Response) return photo;
    // The image goes before the text, which the vision guide recommends.
    content = [
      { type: "image", source: { type: "base64", media_type: "image/jpeg", data: toBase64(photo) } },
      { type: "text", text: userInstruction("image", todayManila(deps.now())) },
    ];
  } else {
    content = [{
      type: "text",
      text: `${userInstruction("text", todayManila(deps.now()), body.source)}\n\n<message>\n${body.text}\n</message>`,
    }];
  }

  let message: ModelMessage;
  try {
    message = await deps.messages.create({
      model: MODEL,
      max_tokens: MAX_TOKENS,
      system: SYSTEM_PROMPT,
      output_config: { effort: EFFORT, format: { type: "json_schema", schema: schemaFor(body.mode) } },
      messages: [{ role: "user", content }],
    });
  } catch (e) {
    return modelFailure(e);
  }

  // Only sizes are logged: never the photo, the message text, or what was read from them.
  console.log(JSON.stringify({
    mode: body.mode,
    stop_reason: message.stop_reason,
    input_tokens: message.usage?.input_tokens,
    output_tokens: message.usage?.output_tokens,
  }));

  const result = readReply(body.mode, message);
  return result instanceof Response ? result : json(200, result);
}
