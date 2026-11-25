// Dashboard math (RFC-008). Pure functions: no DOM, no network, money in integer centavos.
// The dashboard computes everything from the same rows the transactions list shows.
//
// Definitions (shared with csv.js -> csvTotals, so the tiles and the CSV export cannot disagree):
//   * only rows that are not deleted;
//   * spent = sum of `amount` on type 'expense'; income = sum on type 'income';
//   * transfers move money between wallets and count as neither (they do count in wallet balances);
//   * net = income - spent; vat = sum of `vat` (null -> 0) on expense rows;
//   * receipts = non-deleted, non-transfer rows that have a receipt photo (`receipt_path`).
// These are the same rules as the phone's Ledger.monthTotals and GroupLedger.memberTotals.
import { normCentavos } from './money.js';
import { CATEGORIES, categoryLabel } from './categories.js';

export const MONTH_NAMES = [
  'January', 'February', 'March', 'April', 'May', 'June',
  'July', 'August', 'September', 'October', 'November', 'December',
];
export const MONTH_SHORT = MONTH_NAMES.map((name) => name.slice(0, 3));

const DATE_START = /^(\d{4})-(\d{2})-(\d{2})/;
const EXPENSE_SLUGS = new Set(CATEGORIES.expense.map((c) => c.slug));

const pad2 = (n) => String(n).padStart(2, '0');

// Same test as csvTotals: a row is live unless it is explicitly deleted.
export function isLive(t) {
  return !!t && t.deleted !== true;
}

// 'YYYY-MM-DD' as a string, never through Date: a Date would shift the day with the time zone.
function dateOf(t) {
  const m = typeof t.date === 'string' ? DATE_START.exec(t.date) : null;
  return m ? { year: Number(m[1]), month: Number(m[2]) } : null;
}

// The category an expense is counted under: a known expense slug, else 'other' (same as the phone).
export function expenseCategory(slug) {
  return EXPENSE_SLUGS.has(slug) ? slug : 'other';
}

function lowerLabel(s) {
  return String(s).toLowerCase();
}

// Biggest first; ties by label (ignoring case), then slug, so the order never jumps around.
function byAmountThenLabel(a, b) {
  return (b.spent - a.spent)
    || (lowerLabel(a.label) < lowerLabel(b.label) ? -1 : lowerLabel(a.label) > lowerLabel(b.label) ? 1 : 0)
    || (a.category < b.category ? -1 : a.category > b.category ? 1 : 0);
}

// Totals for a set of rows. `entries` counts the rows that carry money (expense or income); it is
// what the dashboard uses to decide a period is empty.
export function summarize(txns) {
  const out = { spent: 0, income: 0, net: 0, vat: 0, receipts: 0, entries: 0 };
  for (const t of txns || []) {
    if (!isLive(t) || t.type === 'transfer') continue;
    if (t.type === 'expense') {
      out.spent += normCentavos(t.amount);
      out.vat += normCentavos(t.vat);
      out.entries += 1;
    } else if (t.type === 'income') {
      out.income += normCentavos(t.amount);
      out.entries += 1;
    }
    if (typeof t.receipt_path === 'string' && t.receipt_path.trim() !== '') out.receipts += 1;
  }
  out.net = out.income - out.spent;
  return out;
}

// period = { kind: 'month', year, month } (month 1-12) or { kind: 'year', year }.
export function periodKey(period) {
  return period.kind === 'year' ? String(period.year) : `${period.year}-${pad2(period.month)}`;
}

export function periodLabel(period) {
  return period.kind === 'year' ? String(period.year) : `${MONTH_NAMES[period.month - 1]} ${period.year}`;
}

function validPeriod(period) {
  if (!period || !Number.isInteger(period.year)) return false;
  if (period.kind === 'year') return true;
  return period.kind === 'month' && Number.isInteger(period.month) && period.month >= 1 && period.month <= 12;
}

// Live rows dated inside the period. Compares the 'YYYY-MM-DD' text, so there are no time-zone surprises.
export function inPeriod(txns, period) {
  if (!validPeriod(period)) return [];
  return (txns || []).filter((t) => {
    if (!isLive(t)) return false;
    const d = dateOf(t);
    if (!d || d.year !== period.year) return false;
    return period.kind === 'year' || d.month === period.month;
  });
}

// Twelve buckets, January first: how much was spent in each month of `year`.
export function monthlySpend(txns, year) {
  const months = Array.from({ length: 12 }, (_, i) => ({ month: i + 1, spent: 0 }));
  for (const t of txns || []) {
    if (!isLive(t) || t.type !== 'expense') continue;
    const d = dateOf(t);
    if (d && d.year === year) months[d.month - 1].spent += normCentavos(t.amount);
  }
  return months;
}

// Spending per expense category, biggest first. A category with nothing spent is left out.
export function spendByCategory(txns) {
  const totals = new Map();
  for (const t of txns || []) {
    if (!isLive(t) || t.type !== 'expense') continue;
    const slug = expenseCategory(t.category);
    totals.set(slug, (totals.get(slug) || 0) + normCentavos(t.amount));
  }
  return [...totals]
    .filter(([, spent]) => spent > 0)
    .map(([category, spent]) => ({ category, label: categoryLabel(category), spent }))
    .sort(byAmountThenLabel);
}

// Per member, in the order of `members` ([{ id, name }]): spent, income, net and the category they
// spent most on (null when they spent nothing). A member with no rows gets zeros. Rows from people
// who are not in `members` are ignored.
export function memberStats(txns, members) {
  return (members || []).map((m) => {
    const rows = (txns || []).filter((t) => t && t.owner_id === m.id);
    const { spent, income, net, entries } = summarize(rows);
    const top = spendByCategory(rows)[0] || null;
    return { id: m.id, name: m.name, spent, income, net, entries, topCategory: top };
  });
}

// For each member (in order): their spending in each of the 12 months of `year`.
export function monthlyByMember(txns, members, year) {
  return (members || []).map((m) => ({
    id: m.id,
    name: m.name,
    months: monthlySpend((txns || []).filter((t) => t && t.owner_id === m.id), year).map((b) => b.spent),
  }));
}

// DESIGN.md: balance = opening_balance + income - expense - transfers out + transfers in, over
// non-deleted rows and ALL time. Archived (deleted) wallets get no balance; a row that points at a
// wallet we have no balance for changes nothing. Same as the phone's Balances.forWallets.
export function walletBalances(wallets, txns) {
  const live = (wallets || []).filter((w) => w && w.deleted !== true);
  const balance = new Map(live.map((w) => [w.id, normCentavos(w.opening_balance)]));
  const move = (walletId, delta) => {
    if (balance.has(walletId)) balance.set(walletId, balance.get(walletId) + delta);
  };
  for (const t of txns || []) {
    if (!isLive(t)) continue;
    const amount = normCentavos(t.amount);
    if (t.type === 'expense') move(t.wallet_id, -amount);
    else if (t.type === 'income') move(t.wallet_id, amount);
    else if (t.type === 'transfer') {
      move(t.wallet_id, -amount);
      if (t.to_wallet_id) move(t.to_wallet_id, amount);
    }
  }
  return live.map((w) => ({
    id: w.id, owner_id: w.owner_id, name: w.name, type: w.type, balance: balance.get(w.id),
  }));
}

// Years and months that have at least one live row, newest first. Months are 'YYYY-MM'.
export function periodsAvailable(txns) {
  const years = new Set();
  const months = new Set();
  for (const t of txns || []) {
    if (!isLive(t)) continue;
    const d = dateOf(t);
    if (!d) continue;
    years.add(d.year);
    months.add(`${d.year}-${pad2(d.month)}`);
  }
  return {
    years: [...years].sort((a, b) => b - a),
    months: [...months].sort().reverse(),
  };
}

// What the period picker offers for `kind` ('month' or 'year'): every period with data plus the one
// that contains `today` ('YYYY-MM-DD'), newest first.
export function periodOptions(txns, kind, today) {
  const { years, months } = periodsAvailable(txns);
  const now = DATE_START.exec(today);
  const nowYear = now ? Number(now[1]) : null;
  const nowMonth = now ? Number(now[2]) : null;
  if (kind === 'year') {
    const all = new Set(years);
    if (nowYear !== null) all.add(nowYear);
    return [...all].sort((a, b) => b - a).map((year) => ({ kind: 'year', year }));
  }
  const all = new Set(months);
  if (nowYear !== null) all.add(`${nowYear}-${pad2(nowMonth)}`);
  return [...all].sort().reverse().map((key) => ({
    kind: 'month', year: Number(key.slice(0, 4)), month: Number(key.slice(5, 7)),
  }));
}

