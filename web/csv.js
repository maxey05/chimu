// CSV export for the accountant (DESIGN.md -> Web dashboard -> Export). Pure functions, no DOM.
// The file starts with a UTF-8 BOM so Excel shows ₱ and Ñ, lines end with CRLF, and money is pesos
// with 2 decimals (never floats: amounts are integer centavos until the last step).
import { normCentavos, pesosPlain } from './money.js';
import { categoryLabel } from './categories.js';

// Written as a code point so the invisible character is never pasted into the source.
const BOM = String.fromCharCode(0xfeff);

export const CSV_COLUMNS = [
  'date', 'owner', 'type', 'wallet', 'category', 'merchant', 'amount', 'is_business',
  'seller_tin', 'invoice_no', 'vatable_sales', 'vat', 'vat_exempt', 'ref_no', 'note', 'photo',
];

// Totals definitions (the dashboard uses this same function so the two can never disagree):
// only non-deleted rows; spent = expense amounts; income = income amounts; transfers are left out
// of spent, income and net; net = income - spent; vat = sum of `vat` (null -> 0) on expense rows.
export function csvTotals(txns) {
  const totals = { spent: 0, income: 0, net: 0, vat: 0 };
  for (const t of txns || []) {
    if (!t || t.deleted === true) continue;
    if (t.type === 'expense') {
      totals.spent += normCentavos(t.amount);
      totals.vat += normCentavos(t.vat);
    } else if (t.type === 'income') {
      totals.income += normCentavos(t.amount);
    }
  }
  totals.net = totals.income - totals.spent;
  return totals;
}

// Spreadsheet formula injection: a text cell that starts with = + - @ (or a tab / carriage return)
// would be run as a formula by Excel. Prefix such cells with an apostrophe so they stay text.
const FORMULA_START = /^[\t\r ]*[=+\-@]|^[\t\r]/;
export function safeText(value) {
  const s = value === null || value === undefined ? '' : String(value);
  return FORMULA_START.test(s) ? `'${s}` : s;
}

// One CSV field: quoted when it holds a comma, a double quote or a line break; quotes doubled.
export function csvField(value) {
  const s = value === null || value === undefined ? '' : String(value);
  return /[",\r\n]/.test(s) ? `"${s.replace(/"/g, '""')}"` : s;
}

function moneyCell(value) {
  return value === null || value === undefined ? '' : pesosPlain(normCentavos(value));
}

// ctx: { members: [{ id, name }], wallets: [{ id, name }] } (all wallets, archived ones too).
// Returns the whole file as a string: BOM + header + one row per live transaction + totals rows.
export function buildCsv(txns, ctx = {}) {
  const memberName = new Map((ctx.members || []).map((m) => [m.id, m.name]));
  const walletName = new Map((ctx.wallets || []).map((w) => [w.id, w.name]));
  const rows = (txns || []).filter((t) => t && t.deleted !== true);
  const lines = [CSV_COLUMNS.join(',')];

  const walletText = (t) => {
    const from = walletName.get(t.wallet_id) || '';
    if (t.type !== 'transfer') return from;
    return `${from} -> ${walletName.get(t.to_wallet_id) || ''}`;
  };

  for (const t of rows) {
    lines.push([
      t.date,
      safeText(memberName.get(t.owner_id) || ''),
      t.type,
      safeText(walletText(t)),
      safeText(categoryLabel(t.category)),
      safeText(t.merchant),
      moneyCell(t.amount),
      t.is_business ? 'yes' : 'no',
      safeText(t.seller_tin),
      safeText(t.invoice_no),
      moneyCell(t.vatable_sales),
      moneyCell(t.vat),
      moneyCell(t.vat_exempt),
      safeText(t.ref_no),
      safeText(t.note),
      safeText(t.receipt_path),
    ].map(csvField).join(','));
  }

  // Totals: labelled rows with type = TOTAL. Spent, income and net sit in the amount column; the VAT
  // total sits in the vat column. Same numbers as csvTotals, which the dashboard also uses.
  const totals = csvTotals(rows);
  const totalRow = (label, { amount = null, vat = null }) => {
    const cells = new Array(CSV_COLUMNS.length).fill('');
    cells[CSV_COLUMNS.indexOf('type')] = 'TOTAL';
    cells[CSV_COLUMNS.indexOf('merchant')] = label;
    if (amount !== null) cells[CSV_COLUMNS.indexOf('amount')] = pesosPlain(amount);
    if (vat !== null) cells[CSV_COLUMNS.indexOf('vat')] = pesosPlain(vat);
    return cells.map(csvField).join(',');
  };
  lines.push(totalRow('Total spent', { amount: totals.spent }));
  lines.push(totalRow('Total income', { amount: totals.income }));
  lines.push(totalRow('Net (income minus spent)', { amount: totals.net }));
  lines.push(totalRow('VAT on expenses', { vat: totals.vat }));

  return BOM + lines.join('\r\n') + '\r\n';
}
