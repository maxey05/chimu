import assert from "node:assert/strict";
import { Buffer } from "node:buffer";
import { type Deps, handle, MAX_IMAGE_BYTES, type ModelMessage } from "./handler.ts";
import { MODEL, schemaFor } from "./schema.ts";

const ME = "11111111-1111-4111-8111-111111111111";
const OTHER = "22222222-2222-4222-8222-222222222222";
const TXN = "33333333-3333-4333-8333-333333333333";
const PATH = `${ME}/${TXN}.jpg`;
const JWT = "header.payload.signature";
const JPEG = new Uint8Array([0xff, 0xd8, 0xff, 0xe0, 1, 2, 3, 4, 5]);

const GOOD_REPLY = {
  type: "expense", amount: 450.5, date: "2026-11-21", merchant: "Mang Inasal", category: "food", ref_no: null,
  seller_tin: "123-456-789-00000", invoice_no: "000123", vatable_sales: 402.23, vat: 48.27, vat_exempt: null, payment_method: "cash",
};

/** Everything the fakes saw, and the knobs to make them misbehave. */
function setup(over: Partial<{
  authStatus: number; authThrows: boolean; userId: string; storageStatus: number; storageBytes: Uint8Array;
  reply: ModelMessage; modelThrows: unknown; noModel: boolean;
}> = {}) {
  const seen = { fetches: [] as Array<{ url: string; headers: Record<string, string> }>, params: null as Record<string, unknown> | null, modelCalls: 0 };
  const reply: ModelMessage = over.reply ?? {
    stop_reason: "end_turn", content: [{ type: "text", text: JSON.stringify(GOOD_REPLY) }], usage: { input_tokens: 1500, output_tokens: 90 },
  };
  const deps: Deps = {
    supabaseUrl: "https://proj.supabase.co",
    anonKey: "anon-key",
    messages: over.noModel ? null : {
      create: (params) => {
        seen.modelCalls++;
        seen.params = params;
        if (over.modelThrows !== undefined) return Promise.reject(over.modelThrows);
        return Promise.resolve(reply);
      },
    },
    fetch: ((input: string | URL | Request, init?: RequestInit) => {
      const url = String(input);
      seen.fetches.push({ url, headers: { ...(init?.headers as Record<string, string>) } });
      if (url.endsWith("/auth/v1/user")) {
        if (over.authThrows) return Promise.reject(new TypeError("network down"));
        const status = over.authStatus ?? 200;
        return Promise.resolve(new Response(status === 200 ? JSON.stringify({ id: over.userId ?? ME }) : "{}", { status }));
      }
      const status = over.storageStatus ?? 200;
      return Promise.resolve(new Response(status === 200 ? (over.storageBytes ?? JPEG).slice() : "{}", { status }));
    }) as typeof fetch,
    now: () => Date.UTC(2026, 10, 23, 12, 0, 0),
  };
  return { deps, seen };
}

function post(body: unknown, headers: Record<string, string> = { Authorization: `Bearer ${JWT}` }): Request {
  return new Request("http://localhost/extract", { method: "POST", headers, body: typeof body === "string" ? body : JSON.stringify(body) });
}

async function body(res: Response) {
  return await res.json() as Record<string, unknown>;
}

const IMAGE = { mode: "image", path: PATH };

// --- sign-in -------------------------------------------------------------------------------------

Deno.test("only POST is accepted", async () => {
  const { deps } = setup();
  assert.equal((await handle(new Request("http://localhost/extract"), deps)).status, 405);
});

Deno.test("a missing or malformed Authorization header is a 401 and nothing else is called", async () => {
  for (const headers of [{} as Record<string, string>, { Authorization: "Basic abc" }, { Authorization: "Bearer " }, { Authorization: "Bearer a b" }]) {
    const { deps, seen } = setup();
    const res = await handle(post(IMAGE, headers), deps);
    assert.equal(res.status, 401);
    assert.equal((await body(res)).error, "unauthorized");
    assert.equal(seen.fetches.length, 0);
    assert.equal(seen.modelCalls, 0);
  }
});

Deno.test("a token Supabase Auth rejects (expired, forged, or the anon key) is a 401", async () => {
  for (const authStatus of [400, 401, 403]) {
    const { deps, seen } = setup({ authStatus });
    const res = await handle(post(IMAGE), deps);
    assert.equal(res.status, 401);
    assert.equal(seen.modelCalls, 0);
  }
});

Deno.test("trouble reaching Supabase Auth is a retryable 502, not a 401", async () => {
  assert.equal((await handle(post(IMAGE), setup({ authStatus: 500 }).deps)).status, 502);
  assert.equal((await handle(post(IMAGE), setup({ authThrows: true }).deps)).status, 502);
});

Deno.test("the token is checked with the project's anon key", async () => {
  const { deps, seen } = setup();
  await handle(post(IMAGE), deps);
  assert.equal(seen.fetches[0].url, "https://proj.supabase.co/auth/v1/user");
  assert.equal(seen.fetches[0].headers.apikey, "anon-key");
  assert.equal(seen.fetches[0].headers.Authorization, `Bearer ${JWT}`);
});

Deno.test("missing configuration is a 500 that does not say which secret is missing", async () => {
  const res = await handle(post(IMAGE), setup({ noModel: true }).deps);
  assert.equal(res.status, 500);
  assert.ok(!JSON.stringify(await body(res)).includes("ANTHROPIC"));
});

// --- request body --------------------------------------------------------------------------------

Deno.test("bad bodies are a 400 and never reach Storage or the model", async () => {
  const bad: unknown[] = [
    "not json", "[]", "null", {}, { mode: "audio" },
    { mode: "image" }, { mode: "image", path: 5 }, { mode: "image", path: "../../etc/passwd" },
    { mode: "image", path: `${ME}/../${OTHER}/${TXN}.jpg` }, { mode: "image", path: `${ME}/${TXN}.png` },
    { mode: "image", path: `${ME}/${TXN}.jpg/extra` }, { mode: "image", path: `${ME}/${TXN}.jpg?x=1` },
    { mode: "text" }, { mode: "text", text: "" }, { mode: "text", text: "   " }, { mode: "text", text: 5 },
    { mode: "text", text: "x".repeat(2001) }, { mode: "text", text: "hi", source: 5 }, { mode: "text", text: "hi", source: "a/b" },
  ];
  for (const b of bad) {
    const { deps, seen } = setup();
    const res = await handle(post(b), deps);
    assert.equal(res.status, 400, JSON.stringify(b));
    assert.equal(seen.fetches.length, 1, "only the sign-in check ran");
    assert.equal(seen.modelCalls, 0);
  }
});

Deno.test("an oversized body is a 413", async () => {
  const res = await handle(post({ mode: "text", text: "x", pad: "y".repeat(9000) }), setup().deps);
  assert.equal(res.status, 413);
});

Deno.test("a photo path in someone else's folder is a 403 before anything is downloaded", async () => {
  const { deps, seen } = setup();
  const res = await handle(post({ mode: "image", path: `${OTHER}/${TXN}.jpg` }), deps);
  assert.equal(res.status, 403);
  assert.equal(seen.fetches.length, 1);
  assert.equal(seen.modelCalls, 0);
});

Deno.test("the folder check ignores letter case", async () => {
  const { deps } = setup({ userId: ME.toUpperCase() });
  assert.equal((await handle(post(IMAGE), deps)).status, 200);
});

// --- image mode ----------------------------------------------------------------------------------

Deno.test("image mode downloads with the caller's token and sends the photo, then the text, to the model", async () => {
  const { deps, seen } = setup();
  const res = await handle(post(IMAGE), deps);
  assert.equal(res.status, 200);

  const storage = seen.fetches[1];
  assert.equal(storage.url, `https://proj.supabase.co/storage/v1/object/authenticated/receipts/${PATH}`);
  assert.equal(storage.headers.Authorization, `Bearer ${JWT}`, "the caller's own token, so RLS applies");

  const p = seen.params!;
  assert.equal(p.model, MODEL);
  assert.equal(typeof p.system, "string");
  assert.deepEqual(p.output_config, { effort: "low", format: { type: "json_schema", schema: schemaFor("image") } });
  const content = (p.messages as Array<{ role: string; content: Array<Record<string, unknown>> }>)[0].content;
  assert.equal(content[0].type, "image");
  assert.deepEqual(content[0].source, { type: "base64", media_type: "image/jpeg", data: Buffer.from(JPEG).toString("base64") });
  assert.equal(content[1].type, "text");
  assert.ok(String(content[1].text).includes("2026-11-23"));

  const out = await body(res);
  assert.equal(out.amount, 450.5);
  assert.equal(out.seller_tin, "123-456-789-00000");
  assert.equal(out.vat_exempt, null);
});

Deno.test("a large photo is encoded to base64 correctly", async () => {
  const big = new Uint8Array(300_000).map((_, i) => (i * 31) % 256);
  big[0] = 0xff; big[1] = 0xd8; big[2] = 0xff;
  const { deps, seen } = setup({ storageBytes: big });
  assert.equal((await handle(post(IMAGE), deps)).status, 200);
  const content = (seen.params!.messages as Array<{ content: Array<{ source?: { data: string } }> }>)[0].content;
  assert.equal(content[0].source!.data, Buffer.from(big).toString("base64"));
});

Deno.test("storage problems map to clear statuses", async () => {
  const cases: Array<[number, number, string]> = [[404, 404, "photo_not_found"], [400, 404, "photo_not_found"], [403, 403, "forbidden"], [401, 403, "forbidden"], [500, 502, "storage_unavailable"]];
  for (const [storageStatus, status, error] of cases) {
    const { deps, seen } = setup({ storageStatus });
    const res = await handle(post(IMAGE), deps);
    assert.equal(res.status, status);
    assert.equal((await body(res)).error, error);
    assert.equal(seen.modelCalls, 0);
  }
});

Deno.test("a file that is not a JPEG is a 415 and a huge one a 413, without calling the model", async () => {
  const png = setup({ storageBytes: new Uint8Array([0x89, 0x50, 0x4e, 0x47, 1, 2]) });
  assert.equal((await handle(post(IMAGE), png.deps)).status, 415);
  assert.equal(png.seen.modelCalls, 0);

  const huge = new Uint8Array(MAX_IMAGE_BYTES + 1);
  huge.set([0xff, 0xd8, 0xff]);
  const big = setup({ storageBytes: huge });
  assert.equal((await handle(post(IMAGE), big.deps)).status, 413);
  assert.equal(big.seen.modelCalls, 0);
});

// --- text mode -----------------------------------------------------------------------------------

Deno.test("text mode sends only the message, with the shared-fields schema, and never touches Storage", async () => {
  const reply: ModelMessage = { stop_reason: "end_turn", content: [{ type: "text", text: JSON.stringify({ type: "income", amount: 1000, date: null, merchant: "Ana", category: null, ref_no: "R1" }) }] };
  const { deps, seen } = setup({ reply });
  const res = await handle(post({ mode: "text", text: "You have received PHP 1,000.00 from Ana. Ref R1", source: "gcash" }), deps);
  assert.equal(res.status, 200);
  assert.equal(seen.fetches.length, 1, "only the sign-in check");
  const p = seen.params!;
  assert.deepEqual((p.output_config as { format: { schema: unknown } }).format.schema, schemaFor("text"));
  const text = String(((p.messages as Array<{ content: Array<{ text: string }> }>)[0]).content[0].text);
  assert.ok(text.includes("gcash"));
  assert.ok(text.includes("<message>\nYou have received PHP 1,000.00 from Ana. Ref R1\n</message>"));
  const out = await body(res);
  assert.deepEqual(Object.keys(out), ["type", "amount", "date", "merchant", "category", "ref_no"]);
  assert.equal(out.type, "income");
});

// --- the model's answer --------------------------------------------------------------------------

Deno.test("a refusal or a cut-off answer is a 422 and is never parsed", async () => {
  for (const [stop_reason, error] of [["refusal", "refused"], ["max_tokens", "incomplete"]]) {
    const reply: ModelMessage = { stop_reason, content: [{ type: "text", text: JSON.stringify(GOOD_REPLY) }] };
    const res = await handle(post(IMAGE), setup({ reply }).deps);
    assert.equal(res.status, 422);
    assert.equal((await body(res)).error, error);
  }
});

Deno.test("an answer that is not usable JSON is a 422", async () => {
  for (const content of [[{ type: "text", text: "Sorry, I can't." }], [], [{ type: "text", text: "[1,2]" }], [{ type: "thinking" }]]) {
    const reply: ModelMessage = { stop_reason: "end_turn", content };
    const res = await handle(post(IMAGE), setup({ reply }).deps);
    assert.equal(res.status, 422);
    assert.equal((await body(res)).error, "bad_output");
  }
});

Deno.test("the answer is cleaned before it is returned", async () => {
  const reply: ModelMessage = { stop_reason: "end_turn", content: [{ type: "text", text: JSON.stringify({ ...GOOD_REPLY, amount: -5, date: "2026-02-31", category: "Food" }) }] };
  const out = await body(await handle(post(IMAGE), setup({ reply }).deps));
  assert.equal(out.amount, null);
  assert.equal(out.date, null);
  assert.equal(out.category, "food");
});

Deno.test("model failures map to statuses the phone can act on", async () => {
  const cases: Array<[unknown, number, string]> = [
    [{ status: 429 }, 503, "model_busy"], [{ status: 529 }, 503, "model_busy"],
    [{ status: 500 }, 502, "model_unavailable"], [{ status: 503 }, 502, "model_unavailable"],
    [new Error("connection reset"), 502, "model_unavailable"],
    [{ status: 400 }, 422, "unreadable"], [{ status: 413 }, 422, "unreadable"],
    [{ status: 401 }, 500, "server_misconfigured"], [{ status: 403 }, 500, "server_misconfigured"],
  ];
  for (const [modelThrows, status, error] of cases) {
    const res = await handle(post(IMAGE), setup({ modelThrows }).deps);
    assert.equal(res.status, status, JSON.stringify(modelThrows));
    assert.equal((await body(res)).error, error);
  }
});

Deno.test("error bodies never echo the photo, the text or the key", async () => {
  const res = await handle(post({ mode: "text", text: "secret-sentinel-text" }), setup({ modelThrows: { status: 401 } }).deps);
  const text = JSON.stringify(await body(res));
  assert.ok(!text.includes("secret-sentinel-text"));
  assert.ok(!text.includes("anon-key"));
});
