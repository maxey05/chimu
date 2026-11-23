// Runs the real index.ts as its own process, with fake Supabase and Anthropic servers on localhost.
// This is the check for "signed-out or invalid JWT -> 401 from the function" and it also proves the
// request that leaves through the real Anthropic SDK has the shape the API expects.
//
//   deno test --allow-net --allow-env --allow-run --allow-read supabase/functions/extract

import assert from "node:assert/strict";
import { Buffer } from "node:buffer";

const ME = "11111111-1111-4111-8111-111111111111";
const TXN = "33333333-3333-4333-8333-333333333333";
const PATH = `${ME}/${TXN}.jpg`;
const GOOD_JWT = "good.user.token";
const JPEG = new Uint8Array([0xff, 0xd8, 0xff, 0xe0, 9, 8, 7]);

const REPLY = {
  type: "expense", amount: 99.5, date: "2026-11-20", merchant: "7-Eleven", category: "groceries", ref_no: null,
  seller_tin: null, invoice_no: null, vatable_sales: null, vat: null, vat_exempt: null, payment_method: null,
};

interface Seen { anthropic: Array<{ headers: Headers; body: Record<string, any> }>; storageAuth: string[] }

function startFakes(anthropicStatus: () => number) {
  const seen: Seen = { anthropic: [], storageAuth: [] };
  const supabase = Deno.serve({ port: 0, hostname: "127.0.0.1", onListen() {} }, (req) => {
    const url = new URL(req.url);
    const auth = req.headers.get("Authorization") ?? "";
    if (url.pathname === "/auth/v1/user") {
      return auth === `Bearer ${GOOD_JWT}` ? Response.json({ id: ME }) : Response.json({ msg: "bad_jwt" }, { status: 403 });
    }
    if (url.pathname === `/storage/v1/object/authenticated/receipts/${PATH}`) {
      seen.storageAuth.push(auth);
      return new Response(JPEG.slice());
    }
    return new Response("not found", { status: 404 });
  });
  const anthropic = Deno.serve({ port: 0, hostname: "127.0.0.1", onListen() {} }, async (req) => {
    seen.anthropic.push({ headers: req.headers, body: await req.json() });
    const status = anthropicStatus();
    if (status !== 200) {
      return Response.json({ type: "error", error: { type: "overloaded_error", message: "busy" } }, { status });
    }
    return Response.json({
      id: "msg_1", type: "message", role: "assistant", model: "claude-opus-5-5", stop_reason: "end_turn", stop_sequence: null,
      content: [{ type: "text", text: JSON.stringify(REPLY) }], usage: { input_tokens: 10, output_tokens: 5 },
    });
  });
  return { seen, supabase, anthropic };
}

async function startFunction(supabasePort: number, anthropicPort: number, withKey = true) {
  const port = 20000 + Math.floor(Math.random() * 20000);
  const env: Record<string, string> = {
    ...Deno.env.toObject(),
    SUPABASE_URL: `http://127.0.0.1:${supabasePort}`,
    SUPABASE_ANON_KEY: "anon-key",
    ANTHROPIC_BASE_URL: `http://127.0.0.1:${anthropicPort}`,
    DENO_SERVE_ADDRESS: `tcp:127.0.0.1:${port}`,
  };
  if (withKey) env.ANTHROPIC_API_KEY = "sk-test-key"; else delete env.ANTHROPIC_API_KEY;
  const child = new Deno.Command(Deno.execPath(), {
    args: ["run", "--no-lock", "--allow-net", "--allow-env", "--allow-read", new URL("./index.ts", import.meta.url).pathname],
    env, stdout: "null", stderr: "null",
  }).spawn();
  const base = `http://127.0.0.1:${port}`;
  for (let i = 0; i < 100; i++) {
    try {
      await (await fetch(base, { method: "GET" })).body?.cancel();
      return { base, stop: async () => { child.kill(); await child.status; } };
    } catch {
      await new Promise((r) => setTimeout(r, 100));
    }
  }
  child.kill();
  throw new Error("the function did not start");
}

const call = (base: string, body: unknown, token?: string) =>
  fetch(base, { method: "POST", headers: token ? { Authorization: `Bearer ${token}` } : {}, body: JSON.stringify(body) });

Deno.test("the real function: 401 when signed out or the token is bad, 200 with a good one", async () => {
  let anthropicStatus = 200;
  const fakes = startFakes(() => anthropicStatus);
  const fn = await startFunction(fakes.supabase.addr.port, fakes.anthropic.addr.port);
  try {
    const image = { mode: "image", path: PATH };

    const signedOut = await call(fn.base, image);
    assert.equal(signedOut.status, 401);
    await signedOut.body?.cancel();

    const anon = await call(fn.base, image, "anon-key");
    assert.equal(anon.status, 401);
    await anon.body?.cancel();
    assert.equal(fakes.seen.anthropic.length, 0, "the model is never called without a valid sign-in");
    assert.equal(fakes.seen.storageAuth.length, 0);

    const ok = await call(fn.base, image, GOOD_JWT);
    assert.equal(ok.status, 200);
    const result = await ok.json();
    assert.equal(result.amount, 99.5);
    assert.equal(result.merchant, "7-Eleven");
    assert.equal(result.seller_tin, null);
    assert.deepEqual(fakes.seen.storageAuth, [`Bearer ${GOOD_JWT}`]);

    // What the real SDK actually sent to the API.
    const sent = fakes.seen.anthropic[0];
    assert.equal(sent.headers.get("authorization"), "Bearer sk-test-key");
    assert.equal(sent.body.model, "claude-opus-5-5");
    assert.equal(sent.body.output_config.effort, "low");
    assert.equal(sent.body.output_config.format.type, "json_schema");
    assert.equal(sent.body.output_config.format.schema.additionalProperties, false);
    assert.equal(sent.body.messages[0].content[0].type, "image");
    assert.equal(sent.body.messages[0].content[0].source.data, Buffer.from(JPEG).toString("base64"));
    assert.equal(typeof sent.body.system, "string");

    const text = await call(fn.base, { mode: "text", text: "You have sent PHP 50.00 to Mang Juan", source: "gcash" }, GOOD_JWT);
    assert.equal(text.status, 200);
    await text.body?.cancel();
    assert.equal(fakes.seen.anthropic[1].body.messages[0].content[0].type, "text");
    assert.equal(fakes.seen.storageAuth.length, 1, "text mode does not read Storage");

    anthropicStatus = 529;
    const busy = await call(fn.base, image, GOOD_JWT);
    assert.equal(busy.status, 503);
    await busy.body?.cancel();
  } finally {
    await fn.stop();
    await fakes.supabase.shutdown();
    await fakes.anthropic.shutdown();
  }
});

Deno.test("the real function without ANTHROPIC_API_KEY is a 500, not a crash", async () => {
  const fakes = startFakes(() => 200);
  const fn = await startFunction(fakes.supabase.addr.port, fakes.anthropic.addr.port, false);
  try {
    const res = await call(fn.base, { mode: "image", path: PATH }, GOOD_JWT);
    assert.equal(res.status, 500);
    await res.body?.cancel();
  } finally {
    await fn.stop();
    await fakes.supabase.shutdown();
    await fakes.anthropic.shutdown();
  }
});
