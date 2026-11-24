// Validation of the edit form. Pure: raw field text in, either a clean update payload or per-field
// errors out. Money is typed like on the phone (1,234.50, at most 2 decimals) and becomes integer centavos.
import { parsePesos } from './money.js';
import { categoriesFor } from './categories.js';

export const LIMITS = { merchant: 120, note: 500, seller_tin: 30, invoice_no: 40, ref_no: 40 };
export const TYPES = ['expense', 'income', 'transfer'];

export function isRealDate(text) {
  if (typeof text !== 'string' || !/^\d{4}-\d{2}-\d{2}$/.test(text)) return false;
  const [y, m, d] = text.split('-').map(Number);
  const dt = new Date(Date.UTC(y, m - 1, d));
  return dt.getUTCFullYear() === y && dt.getUTCMonth() === m - 1 && dt.getUTCDate() === d;
}

const orNull = (s) => {
  const v = (s ?? '').trim();
  return v === '' ? null : v;
};

// v: raw form values (strings, plus is_business boolean).
// ctx: { ownWalletIds: [ids the user may pick], original: the row being edited }.
// Returns { errors: { field: message }, payload } where payload is null when there are errors.
export function validateEdit(v, ctx) {
  const errors = {};
  const original = ctx.original || {};
  const own = new Set(ctx.ownWalletIds || []);

  if (!TYPES.includes(v.type)) errors.type = 'Choose expense, income or transfer.';

  if (!isRealDate(v.date)) errors.date = 'Enter a real date.';

  const amount = parsePesos(v.amount ?? '');
  if (amount === null) errors.amount = 'Enter an amount like 1,234.50 (at most 2 decimals).';

  const walletOk = (id) => id && (own.has(id) || id === original.wallet_id);
  if (!walletOk(v.wallet_id)) errors.wallet_id = 'Choose a wallet.';

  let toWallet = null;
  if (v.type === 'transfer') {
    if (!(v.to_wallet_id && (own.has(v.to_wallet_id) || v.to_wallet_id === original.to_wallet_id))) {
      errors.to_wallet_id = 'Choose the wallet the money goes to.';
    } else if (v.to_wallet_id === v.wallet_id) {
      errors.to_wallet_id = 'A transfer needs two different wallets.';
    } else {
      toWallet = v.to_wallet_id;
    }
  }

  let category = null;
  if (v.type === 'expense' || v.type === 'income') {
    const slug = orNull(v.category);
    if (slug !== null) {
      const allowed = new Set(categoriesFor(v.type).map((c) => c.slug));
      // a category this page does not know (e.g. from an older app) may stay as it is
      if (v.type === original.type && slug === original.category) allowed.add(slug);
      if (allowed.has(slug)) category = slug;
      else errors.category = `Choose a category for ${v.type}.`;
    }
  }

  const text = {};
  for (const [field, max] of Object.entries(LIMITS)) {
    const val = orNull(v[field]);
    if (val !== null && Array.from(val).length > max) errors[field] = `At most ${max} characters.`;
    text[field] = val;
  }

  const money = {};
  for (const field of ['vatable_sales', 'vat', 'vat_exempt']) {
    const raw = (v[field] ?? '').trim();
    if (raw === '') { money[field] = null; continue; }
    const c = parsePesos(raw);
    if (c === null) errors[field] = 'Enter an amount like 1,234.50, or leave it empty.';
    money[field] = c;
  }

  if (Object.keys(errors).length) return { errors, payload: null };
  return {
    errors,
    payload: {
      type: v.type,
      date: v.date,
      amount,
      wallet_id: v.wallet_id,
      to_wallet_id: toWallet,
      category,
      merchant: text.merchant,
      note: text.note,
      is_business: v.is_business === true,
      seller_tin: text.seller_tin,
      invoice_no: text.invoice_no,
      vatable_sales: money.vatable_sales,
      vat: money.vat,
      vat_exempt: money.vat_exempt,
      ref_no: text.ref_no,
    },
  };
}

// The updated_at for an edit: now, but never older than what the server already holds, so a phone
// clock that runs a little fast does not make the next save lose silently (DESIGN.md -> Sync protocol).
export function nextUpdatedAt(now, row) {
  const raw = Number(row && row.updated_at);
  const stored = Number.isFinite(raw) ? raw : 0;
  return Math.max(now, stored + 1);
}
