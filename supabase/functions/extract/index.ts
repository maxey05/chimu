// `extract`: reads a receipt photo or an e-wallet / bank message with Claude and returns the fields.
// The Anthropic key lives only in this function's secrets (RULES 14): `supabase secrets set ANTHROPIC_API_KEY=...`.
// SUPABASE_URL and SUPABASE_ANON_KEY are provided by Supabase.

import Anthropic from "npm:@anthropic-ai/sdk@1.12.0";
import { handle } from "./handler.ts";

const apiKey = Deno.env.get("ANTHROPIC_API_KEY");
// The default timeout is 10 minutes; a receipt should take seconds, so fail early and let the phone retry.
const client = apiKey ? new Anthropic({ apiKey, timeout: 60_000, maxRetries: 1 }) : null;

Deno.serve((req) =>
  handle(req, {
    supabaseUrl: Deno.env.get("SUPABASE_URL") ?? "",
    anonKey: Deno.env.get("SUPABASE_ANON_KEY") ?? "",
    // `output_config` is sent as built by the handler; the SDK passes it through.
    messages: client ? { create: (params) => client.messages.create(params as never) } : null,
    fetch,
    now: Date.now,
  })
);
