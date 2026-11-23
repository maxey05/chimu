import assert from "node:assert/strict";
import { BadOutput, CATEGORIES, normalize, schemaFor, SYSTEM_PROMPT, userInstruction } from "./schema.ts";

const IMAGE_ONLY = ["seller_tin", "invoice_no", "vatable_sales", "vat", "vat_exempt", "payment_method"];
const SHARED = ["type", "amount", "date", "merchant", "category", "ref_no"];

type Schema = { properties: Record<string, unknown>; required: string[]; additionalProperties: boolean };

Deno.test("text schema has the shared keys only; image schema adds the receipt keys", () => {
  const text = schemaFor("text") as Schema;
  const image = schemaFor("image") as Schema;
  assert.deepEqual(Object.keys(text.properties), SHARED);
  assert.deepEqual(Object.keys(image.properties), [...SHARED, ...IMAGE_ONLY]);
});

Deno.test("every key is required and no extra keys are allowed", () => {
  for (const mode of ["text", "image"] as const) {
    const schema = schemaFor(mode) as Schema;
    assert.deepEqual(schema.required, Object.keys(schema.properties));
    assert.equal(schema.additionalProperties, false);
  }
});

Deno.test("the schema stays inside the API's union-type limit of 16", () => {
  const image = JSON.stringify(schemaFor("image"));
  const unions = (image.match(/"anyOf"/g) ?? []).length + (image.match(/"type":\["/g) ?? []).length;
  assert.ok(unions <= 16, `${unions} union types`);
});

Deno.test("the schema uses no keywords the structured-output API removes", () => {
  const image = JSON.stringify(schemaFor("image"));
  for (const keyword of ["minimum", "maximum", "minLength", "maxLength", "pattern", "format"]) {
    assert.ok(!image.includes(`"${keyword}"`), keyword);
  }
});

Deno.test("the category enum is exactly the phone's slugs", () => {
  const schema = schemaFor("text") as Schema;
  const category = schema.properties.category as { anyOf: Array<{ enum?: string[] }> };
  assert.deepEqual(category.anyOf[0].enum, [...CATEGORIES]);
  assert.equal(CATEGORIES.length, 13);
});

Deno.test("the prompt carries the rules DESIGN.md asks for", () => {
  for (const needle of ["null", "VATable Sales", "VAT-Exempt Sales", "Amount Due", "123-456-789-00000", "You have sent", "Received", "MM/DD/YYYY"]) {
    assert.ok(SYSTEM_PROMPT.includes(needle), needle);
  }
  assert.ok(userInstruction("text", "2026-11-23", "gcash").includes("gcash"));
  assert.ok(userInstruction("image", "2026-11-23").includes("2026-11-23"));
});

Deno.test("normalize keeps good values for an image", () => {
  const out = normalize("image", {
    type: "expense", amount: 1234.5, date: "2026-11-21", merchant: "Jollibee", category: "food", ref_no: "A1",
    seller_tin: "123-456-789-00000", invoice_no: "0001234", vatable_sales: 1102.23, vat: 132.27, vat_exempt: 0, payment_method: "cash",
  });
  assert.deepEqual(out, {
    type: "expense", amount: 1234.5, date: "2026-11-21", merchant: "Jollibee", category: "food", ref_no: "A1",
    seller_tin: "123-456-789-00000", invoice_no: "0001234", vatable_sales: 1102.23, vat: 132.27, vat_exempt: 0, payment_method: "cash",
  });
});

Deno.test("normalize in text mode returns the shared keys only", () => {
  const out = normalize("text", { type: "income", amount: 500, date: null, merchant: "Juan", category: "gift", ref_no: "9", vat: 12 });
  assert.deepEqual(Object.keys(out), SHARED);
  assert.equal(out.type, "income");
});

Deno.test("normalize turns wrong types, bad dates and impossible amounts into null", () => {
  const out = normalize("image", {
    type: "refund", amount: "12.50", date: "2026-02-30", merchant: 42, category: "pets", ref_no: "   ",
    seller_tin: null, invoice_no: {}, vatable_sales: -5, vat: Infinity, vat_exempt: 1e15, payment_method: "bitcoin",
  });
  assert.equal(out.type, "expense");
  assert.equal(out.amount, null);
  assert.equal(out.date, null);
  assert.equal(out.merchant, null);
  assert.equal(out.category, null);
  assert.equal(out.ref_no, null);
  assert.equal(out.seller_tin, null);
  assert.equal(out.invoice_no, null);
  assert.equal(out.vatable_sales, null);
  assert.equal(out.vat, null);
  assert.equal(out.vat_exempt, null);
  assert.equal(out.payment_method, null);
});

Deno.test("normalize accepts any capitalisation of an enum and trims text", () => {
  const out = normalize("text", { type: " EXPENSE ", category: "Groceries", merchant: "  SM Supermarket  ", amount: 10, date: " 2026-11-21 ", ref_no: null });
  assert.equal(out.type, "expense");
  assert.equal(out.category, "groceries");
  assert.equal(out.merchant, "SM Supermarket");
  assert.equal(out.date, "2026-11-21");
});

Deno.test("normalize rounds pesos to centavos and cuts long text", () => {
  const out = normalize("text", { type: "expense", amount: 10.005 + 0.0001, merchant: "x".repeat(500), date: null, category: null, ref_no: "r".repeat(500) });
  assert.equal(out.amount, 10.01);
  assert.equal(out.merchant!.length, 120);
  assert.equal(out.ref_no!.length, 64);
});

Deno.test("normalize accepts leap days and rejects other impossible dates", () => {
  assert.equal(normalize("text", { date: "2028-02-29" }).date, "2028-02-29");
  assert.equal(normalize("text", { date: "2026-02-29" }).date, null);
  assert.equal(normalize("text", { date: "2026-13-01" }).date, null);
  assert.equal(normalize("text", { date: "11/21/2026" }).date, null);
});

Deno.test("normalize rejects a reply that is not an object", () => {
  for (const bad of [null, "text", 5, [], undefined]) assert.throws(() => normalize("text", bad), BadOutput);
});
