// Checks for stats.js and the dashboard (RFC-008). Runs in the browser (test.html) and in Node
// (node web/test-node.mjs). The pieces that need a DOM only run when a `document` exists.
import {
  summarize, inPeriod, monthlySpend, spendByCategory, memberStats, monthlyByMember, walletBalances,
  periodsAvailable, periodOptions, periodKey, periodLabel, expenseCategory,
} from './stats.js';
import { csvTotals, buildCsv, CSV_COLUMNS } from './csv.js';
import { pesosPlain, formatPesos } from './money.js';
import { niceScale, percentText } from './charts.js';
import { renderDashboard, resetDashboardPeriod } from './dashboard.js';

// --- fixtures ---------------------------------------------------------------------------------------

let counter = 0;
function row(over = {}) {
  counter += 1;
  return {
    id: `t${counter}`, owner_id: 'me', type: 'expense', wallet_id: 'w-me', to_wallet_id: null, amount: 1000,
    date: '2026-11-10', category: 'food', merchant: null, is_business: false, vat: null, receipt_path: null,
    deleted: false, ...over,
  };
}

const ME = 'user-me';
const ANA = 'user-ana';
const BEN = 'user-ben';
const MEMBERS = [{ id: ANA, name: 'Ana' }, { id: BEN, name: 'Ben' }, { id: ME, name: 'Matt' }];

// The same data as GroupsTest.memberTotals_matchAManualSum on the phone.
function phoneGroupRows() {
  return [
    row({ owner_id: ANA, amount: 12345, date: '2026-11-02' }),
    row({ owner_id: ANA, amount: 1000, date: '2026-11-30' }),
    row({ owner_id: ANA, amount: 55, date: '2026-11-15' }),
    row({ owner_id: ANA, amount: 500000, type: 'income', date: '2026-11-01', category: 'salary' }),
    row({ owner_id: ANA, amount: 99999, date: '2026-10-31' }),
    row({ owner_id: ANA, amount: 70000, type: 'transfer', date: '2026-11-05', category: null, to_wallet_id: 'w2-ana' }),
    row({ owner_id: ME, amount: 100000, date: '2026-11-09' }),
  ];
}

// Seeded pseudo-random rows so the cross-checks cover many shapes and stay repeatable.
function rng(seed) {
  let s = seed >>> 0;
  return () => { s = (Math.imul(s, 1664525) + 1013904223) >>> 0; return s / 2 ** 32; };
}
const EDGE_DATES = [
  '2025-12-31', '2026-01-01', '2026-01-31', '2026-02-01', '2026-02-28', '2026-03-01', '2026-10-31', '2026-11-01',
  '2026-11-30', '2026-12-01', '2026-12-31', '2027-01-01', '2028-02-29', '2028-03-01',
];
function randomRows(seed, count) {
  const r = rng(seed);
  const pick = (list) => list[Math.floor(r() * list.length)];
  const owners = [ME, ANA, BEN, 'user-stranger'];
  const cats = ['food', 'groceries', 'transport', 'bills', 'shopping', 'health', 'education', 'entertainment', 'travel', 'other', null, 'salary', 'mystery'];
  return Array.from({ length: count }, () => {
    const type = pick(['expense', 'expense', 'expense', 'income', 'income', 'transfer']);
    const month = 1 + Math.floor(r() * 12);
    const date = r() < 0.4 ? pick(EDGE_DATES) : `${pick([2025, 2026, 2027])}-${String(month).padStart(2, '0')}-${String(1 + Math.floor(r() * 28)).padStart(2, '0')}`;
    return row({
      owner_id: pick(owners), type, date, category: type === 'transfer' ? null : pick(cats),
      amount: Math.floor(r() * 500000), vat: r() < 0.5 ? null : Math.floor(r() * 50000),
      receipt_path: r() < 0.3 ? `o/${counter}.jpg` : (r() < 0.1 ? '   ' : null),
      is_business: r() < 0.5, deleted: r() < 0.1,
      wallet_id: pick(['w-a', 'w-b', 'w-c']), to_wallet_id: type === 'transfer' ? pick(['w-a', 'w-b', 'w-c', null]) : null,
    });
  });
}

// --- independent ports of the phone's rules (Ledger.monthTotals, GroupLedger.memberTotals, Balances) ---

function phoneMonthTotals(rows, month) {
  let spent = 0;
  let income = 0;
  for (const r of rows) {
    if (r.deleted || r.date.slice(0, 7) !== month) continue;
    if (r.type === 'expense') spent += r.amount;
    else if (r.type === 'income') income += r.amount;
  }
  return { spent, income };
}
function phoneMemberTotals(rows, members, month) {
  const visible = rows.filter((r) => !r.deleted);
  return members.map((m) => phoneMonthTotals(visible.filter((r) => r.owner_id === m.id), month));
}
function phoneBalances(wallets, rows) {
  const b = new Map();
  for (const w of wallets) if (!w.deleted) b.set(w.id, w.opening_balance);
  const adjust = (id, d) => { if (b.has(id)) b.set(id, b.get(id) + d); };
  for (const r of rows) {
    if (r.deleted) continue;
    if (r.type === 'expense') adjust(r.wallet_id, -r.amount);
    else if (r.type === 'income') adjust(r.wallet_id, r.amount);
    else { adjust(r.wallet_id, -r.amount); if (r.to_wallet_id) adjust(r.to_wallet_id, r.amount); }
  }
  return b;
}

const MONTH = (y, m) => ({ kind: 'month', year: y, month: m });
const YEAR = (y) => ({ kind: 'year', year: y });

// --- the checks -------------------------------------------------------------------------------------

export function tests(t) {
  // summarize ---------------------------------------------------------------------------------------

  t.check('summarize: spent, income, net, vat, receipts and entries from a hand-worked set', () => {
    const rows = [
      row({ amount: 12345, vat: 1322, receipt_path: 'a/1.jpg' }),
      row({ amount: 5000 }),
      row({ type: 'income', amount: 200000, category: 'salary', receipt_path: 'a/2.jpg' }),
      row({ type: 'income', amount: 1550, category: 'gift', vat: 999 }), // vat on income is not counted
    ];
    t.eq(summarize(rows), { spent: 17345, income: 201550, net: 184205, vat: 1322, receipts: 2, entries: 4 });
  });

  t.check('summarize: transfers count as neither spent, income, net, vat nor receipts', () => {
    const rows = [row({ amount: 1000 }), row({ type: 'transfer', amount: 999999, vat: 5, receipt_path: 'x.jpg', to_wallet_id: 'w2' })];
    t.eq(summarize(rows), { spent: 1000, income: 0, net: -1000, vat: 0, receipts: 0, entries: 1 });
  });

  t.check('summarize: deleted rows are left out of everything', () => {
    const rows = [row({ amount: 700 }), row({ amount: 9000, deleted: true, vat: 90, receipt_path: 'd.jpg' }), row({ type: 'income', amount: 4000, deleted: true })];
    t.eq(summarize(rows), { spent: 700, income: 0, net: -700, vat: 0, receipts: 0, entries: 1 });
  });

  t.check('summarize: net goes negative and zero cleanly', () => {
    t.eq(summarize([row({ amount: 300 }), row({ type: 'income', amount: 100 })]).net, -200);
    t.eq(summarize([row({ amount: 300 }), row({ type: 'income', amount: 300 })]).net, 0);
  });

  t.check('summarize: a null vat counts as 0, a missing field too', () => {
    const noVat = row({ amount: 500 });
    delete noVat.vat;
    t.eq(summarize([row({ amount: 500, vat: null }), noVat]).vat, 0);
  });

  t.check('summarize: a receipt is a non-empty receipt_path; blank or missing is not', () => {
    const rows = [row({ receipt_path: 'a/1.jpg' }), row({ receipt_path: '' }), row({ receipt_path: '   ' }), row({ receipt_path: null }), row({ type: 'income', receipt_path: 'a/3.jpg' })];
    t.eq(summarize(rows).receipts, 2);
  });

  t.check('summarize: float-looking, string and junk amounts are normalised to whole centavos', () => {
    const rows = [row({ amount: 1234.0 }), row({ amount: 10.5 }), row({ amount: '250' }), row({ amount: 'abc' }), row({ amount: null }), row({ amount: NaN })];
    t.eq(summarize(rows).spent, 1234 + 11 + 250);
  });

  t.check('summarize: nothing in, zeros out; it does not change its input', () => {
    t.eq(summarize([]), { spent: 0, income: 0, net: 0, vat: 0, receipts: 0, entries: 0 });
    t.eq(summarize(undefined), { spent: 0, income: 0, net: 0, vat: 0, receipts: 0, entries: 0 });
    t.eq(summarize([null, undefined]), { spent: 0, income: 0, net: 0, vat: 0, receipts: 0, entries: 0 });
    const frozen = [Object.freeze(row({ amount: 5 }))];
    t.eq(summarize(frozen).spent, 5);
  });

  // inPeriod ----------------------------------------------------------------------------------------

  t.check('inPeriod: a month includes its first and last day and nothing around it', () => {
    const rows = [
      row({ date: '2026-10-31' }), row({ date: '2026-11-01' }), row({ date: '2026-11-15' }),
      row({ date: '2026-11-30' }), row({ date: '2026-12-01' }),
    ];
    t.eq(inPeriod(rows, MONTH(2026, 11)).map((r) => r.date), ['2026-11-01', '2026-11-15', '2026-11-30']);
  });

  t.check('inPeriod: a year runs from Jan 1 to Dec 31 (Dec 31 / Jan 1 edges)', () => {
    const rows = [row({ date: '2025-12-31' }), row({ date: '2026-01-01' }), row({ date: '2026-12-31' }), row({ date: '2027-01-01' })];
    t.eq(inPeriod(rows, YEAR(2026)).map((r) => r.date), ['2026-01-01', '2026-12-31']);
    t.eq(inPeriod(rows, MONTH(2026, 12)).map((r) => r.date), ['2026-12-31']);
    t.eq(inPeriod(rows, MONTH(2027, 1)).map((r) => r.date), ['2027-01-01']);
  });

  t.check('inPeriod: Feb 29 of a leap year is February; the same date in a common year does not exist', () => {
    const rows = [row({ date: '2028-02-28' }), row({ date: '2028-02-29' }), row({ date: '2028-03-01' }), row({ date: '2027-02-28' })];
    t.eq(inPeriod(rows, MONTH(2028, 2)).map((r) => r.date), ['2028-02-28', '2028-02-29']);
    t.eq(inPeriod(rows, MONTH(2028, 3)).map((r) => r.date), ['2028-03-01']);
    t.eq(inPeriod(rows, MONTH(2027, 2)).map((r) => r.date), ['2027-02-28']);
  });

  t.check('inPeriod: deleted rows and rows with no usable date are skipped', () => {
    const rows = [
      row({ date: '2026-11-05', deleted: true }), row({ date: null }), row({ date: '' }), row({ date: 'garbage' }),
      row({ date: 20261105 }), row({ date: '2026-11-05' }),
    ];
    t.eq(inPeriod(rows, MONTH(2026, 11)).length, 1);
  });

  t.check('inPeriod: an invalid period matches nothing', () => {
    const rows = [row({ date: '2026-11-05' })];
    t.eq(inPeriod(rows, MONTH(2026, 13)), []);
    t.eq(inPeriod(rows, MONTH(2026, 0)), []);
    t.eq(inPeriod(rows, { kind: 'week', year: 2026 }), []);
    t.eq(inPeriod(rows, null), []);
    t.eq(inPeriod(rows, { kind: 'year', year: '2026' }), []);
    t.eq(inPeriod(undefined, YEAR(2026)), []);
  });

  t.check('period helpers: key and label', () => {
    t.eq(periodKey(MONTH(2026, 3)), '2026-03');
    t.eq(periodKey(YEAR(2026)), '2026');
    t.eq(periodLabel(MONTH(2026, 11)), 'November 2026');
    t.eq(periodLabel(YEAR(2026)), '2026');
  });

  t.check('stats never build a Date, so no time zone can move a day', () => {
    const RealDate = globalThis.Date;
    globalThis.Date = class { constructor() { throw new Error('Date was used'); } static now() { throw new Error('Date.now was used'); } };
    try {
      const rows = [row({ date: '2026-12-31' }), row({ date: '2027-01-01' }), row({ type: 'income', date: '2026-12-31' })];
      inPeriod(rows, MONTH(2026, 12));
      monthlySpend(rows, 2026);
      spendByCategory(rows);
      periodsAvailable(rows);
      periodOptions(rows, 'month', '2026-12-31');
      summarize(rows);
    } finally {
      globalThis.Date = RealDate;
    }
  });

  // monthlySpend ------------------------------------------------------------------------------------

  t.check('monthlySpend: twelve buckets with the right months, boundaries and only expenses', () => {
    const rows = [
      row({ date: '2026-01-01', amount: 100 }), row({ date: '2026-01-31', amount: 20 }),
      row({ date: '2026-02-01', amount: 3 }), row({ date: '2026-12-31', amount: 4000 }),
      row({ date: '2025-12-31', amount: 7 }), row({ date: '2027-01-01', amount: 9 }),
      row({ date: '2026-06-15', type: 'income', amount: 50000 }),
      row({ date: '2026-06-16', type: 'transfer', amount: 60000, to_wallet_id: 'w2' }),
      row({ date: '2026-06-17', amount: 80000, deleted: true }),
    ];
    const buckets = monthlySpend(rows, 2026);
    t.eq(buckets.length, 12);
    t.eq(buckets.map((b) => b.month), [1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12]);
    t.eq(buckets.map((b) => b.spent), [120, 3, 0, 0, 0, 0, 0, 0, 0, 0, 0, 4000]);
  });

  t.check('monthlySpend: the months add up to the year summary, empty input gives twelve zeros', () => {
    const rows = randomRows(7, 300);
    const year = inPeriod(rows, YEAR(2026));
    t.eq(monthlySpend(rows, 2026).reduce((s, b) => s + b.spent, 0), summarize(year).spent);
    t.eq(monthlySpend([], 2026).map((b) => b.spent), new Array(12).fill(0));
  });

  // spendByCategory ---------------------------------------------------------------------------------

  t.check('spendByCategory: biggest first, with labels, summing each category', () => {
    const rows = [
      row({ category: 'food', amount: 10000 }), row({ category: 'food', amount: 5000 }),
      row({ category: 'transport', amount: 30000 }), row({ category: 'health', amount: 2500 }),
    ];
    t.eq(spendByCategory(rows), [
      { category: 'transport', label: 'Transport', spent: 30000 },
      { category: 'food', label: 'Food & Dining', spent: 15000 },
      { category: 'health', label: 'Health', spent: 2500 },
    ]);
  });

  t.check('spendByCategory: ties are ordered by label, whatever order the rows come in', () => {
    const rows = [
      row({ category: 'transport', amount: 1000 }), row({ category: 'food', amount: 1000 }),
      row({ category: 'bills', amount: 1000 }), row({ category: 'health', amount: 500 }),
    ];
    const expected = ['bills', 'food', 'transport', 'health'];
    t.eq(spendByCategory(rows).map((c) => c.category), expected);
    t.eq(spendByCategory([...rows].reverse()).map((c) => c.category), expected);
  });

  t.check('spendByCategory: a missing or unknown category counts as Other, so the bars add up to spent', () => {
    const rows = [
      row({ category: null, amount: 100 }), row({ category: 'mystery', amount: 200 }), row({ category: 'other', amount: 400 }),
      row({ category: 'salary', amount: 800 }), row({ category: 'food', amount: 1 }),
    ];
    const cats = spendByCategory(rows);
    t.eq(cats[0], { category: 'other', label: 'Other', spent: 1500 });
    t.eq(cats.reduce((s, c) => s + c.spent, 0), summarize(rows).spent);
    t.eq(expenseCategory(undefined), 'other');
    t.eq(expenseCategory('groceries'), 'groceries');
  });

  t.check('spendByCategory: income, transfers, deleted rows and zero amounts never show', () => {
    const rows = [
      row({ category: 'food', amount: 100 }), row({ type: 'income', category: 'salary', amount: 9999 }),
      row({ type: 'transfer', category: null, amount: 9999, to_wallet_id: 'w2' }), row({ category: 'travel', amount: 5000, deleted: true }),
      row({ category: 'health', amount: 0 }),
    ];
    t.eq(spendByCategory(rows).map((c) => c.category), ['food']);
    t.eq(spendByCategory([]), []);
  });

  // members -----------------------------------------------------------------------------------------

  t.check('memberStats: matches the phone totals for the same data (GroupsTest.memberTotals_matchAManualSum)', () => {
    // Ana, November: spent 123.45 + 10.00 + 0.55 = 134.00, income 5,000.00. October and the transfer don't count.
    // Matt: spent 1,000.00. Ben has logged nothing.
    const stats = memberStats(inPeriod(phoneGroupRows(), MONTH(2026, 11)), MEMBERS);
    t.eq(stats.map((s) => [s.id, s.spent, s.income]), [[ANA, 13400, 500000], [BEN, 0, 0], [ME, 100000, 0]]);
    t.eq(stats.map((s) => s.name), ['Ana', 'Ben', 'Matt']);
  });

  t.check('memberStats: ignores deleted rows and people who are not in the list; top category per member', () => {
    const rows = [
      row({ owner_id: ANA, category: 'food', amount: 1000 }), row({ owner_id: ANA, category: 'transport', amount: 4000 }),
      row({ owner_id: ANA, category: 'travel', amount: 9000, deleted: true }),
      row({ owner_id: 'user-stranger', amount: 7777 }),
      row({ owner_id: BEN, type: 'income', category: 'salary', amount: 3000 }),
    ];
    const [ana, ben, me] = memberStats(rows, MEMBERS);
    t.eq(ana.topCategory, { category: 'transport', label: 'Transport', spent: 4000 });
    t.eq(ana.spent, 5000);
    t.eq(ben.topCategory, null);
    t.eq(ben.income, 3000);
    t.eq(me, { id: ME, name: 'Matt', spent: 0, income: 0, net: 0, entries: 0, topCategory: null });
    t.eq(memberStats(rows, []), []);
  });

  t.check('memberStats: equals a port of the phone rules (monthTotals/memberTotals) on 20 random data sets x every month', () => {
    for (let seed = 1; seed <= 20; seed += 1) {
      const rows = randomRows(seed, 150);
      for (const y of [2025, 2026, 2027]) {
        for (let m = 1; m <= 12; m += 1) {
          const key = `${y}-${String(m).padStart(2, '0')}`;
          const mine = memberStats(inPeriod(rows, MONTH(y, m)), MEMBERS).map((s) => ({ spent: s.spent, income: s.income }));
          t.eq(mine, phoneMemberTotals(rows, MEMBERS, key), `seed ${seed} ${key}`);
        }
      }
    }
  });

  t.check('monthlyByMember: twelve months per member, in member order, adding up to monthlySpend', () => {
    const rows = [...phoneGroupRows(), row({ owner_id: BEN, date: '2026-03-03', amount: 600 }), row({ owner_id: 'user-stranger', amount: 5 })];
    const per = monthlyByMember(rows, MEMBERS, 2026);
    t.eq(per.map((m) => m.id), [ANA, BEN, ME]);
    t.ok(per.every((m) => m.months.length === 12));
    t.eq(per[0].months[10], 13400); // Ana, November
    t.eq(per[0].months[9], 99999); // Ana, October
    t.eq(per[1].months[2], 600); // Ben, March
    t.eq(per[2].months[10], 100000);
    const everyone = rows.filter((r) => MEMBERS.some((m) => m.id === r.owner_id));
    t.eq(monthlySpend(everyone, 2026).map((b) => b.spent), [0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11].map((i) => per.reduce((s, m) => s + m.months[i], 0)));
  });

  // wallet balances ---------------------------------------------------------------------------------

  t.check('walletBalances: opening + income - expense - transfers out + transfers in (hand worked)', () => {
    const wallets = [
      { id: 'w1', owner_id: ME, name: 'Cash', type: 'cash', opening_balance: 10000, deleted: false },
      { id: 'w2', owner_id: ME, name: 'GCash', type: 'gcash', opening_balance: 0, deleted: false },
    ];
    const rows = [
      row({ wallet_id: 'w1', type: 'income', amount: 5000 }), // w1 15000
      row({ wallet_id: 'w1', type: 'expense', amount: 2000 }), // w1 13000
      row({ wallet_id: 'w1', type: 'transfer', amount: 3000, to_wallet_id: 'w2' }), // w1 10000, w2 3000
      row({ wallet_id: 'w2', type: 'transfer', amount: 700, to_wallet_id: 'w1' }), // w2 2300, w1 10700
      row({ wallet_id: 'w2', type: 'expense', amount: 300 }), // w2 2000
    ];
    t.eq(walletBalances(wallets, rows).map((w) => [w.id, w.balance]), [['w1', 10700], ['w2', 2000]]);
  });

  t.check('walletBalances: counts all time, skips deleted rows, deleted wallets, unknown wallets and empty transfer targets', () => {
    const wallets = [
      { id: 'w1', owner_id: ME, name: 'Cash', type: 'cash', opening_balance: -500, deleted: false },
      { id: 'w9', owner_id: ME, name: 'Old', type: 'bank', opening_balance: 99, deleted: true },
    ];
    const rows = [
      row({ wallet_id: 'w1', date: '2019-01-01', type: 'income', amount: 1000 }), // far in the past still counts
      row({ wallet_id: 'w1', amount: 100, deleted: true }),
      row({ wallet_id: 'w1', type: 'transfer', amount: 200, to_wallet_id: 'w9' }), // only the source moves
      row({ wallet_id: 'w1', type: 'transfer', amount: 50, to_wallet_id: null }),
      row({ wallet_id: 'w1', type: 'transfer', amount: 40, to_wallet_id: 'w1' }), // to itself: no change
      row({ wallet_id: 'nope', amount: 12345 }),
    ];
    const out = walletBalances(wallets, rows);
    t.eq(out.map((w) => w.id), ['w1']);
    t.eq(out[0].balance, -500 + 1000 - 200 - 50);
  });

  t.check('walletBalances: equals a port of the phone Balances.forWallets on random data', () => {
    for (let seed = 1; seed <= 20; seed += 1) {
      const wallets = ['w-a', 'w-b', 'w-c', 'w-d'].map((id, i) => ({ id, owner_id: ME, name: id, type: 'cash', opening_balance: (i - 1) * 12345, deleted: i === 3 }));
      const rows = randomRows(seed, 200);
      const expected = phoneBalances(wallets, rows);
      const got = walletBalances(wallets, rows);
      t.eq(got.map((w) => [w.id, w.balance]), [...expected].map(([id, b]) => [id, b]), `seed ${seed}`);
    }
  });

  t.check('walletBalances: keeps owner, name and type for the list', () => {
    const out = walletBalances([{ id: 'w1', owner_id: ANA, name: 'Maya', type: 'maya', opening_balance: 5, deleted: false }], []);
    t.eq(out, [{ id: 'w1', owner_id: ANA, name: 'Maya', type: 'maya', balance: 5 }]);
  });

  // periods -----------------------------------------------------------------------------------------

  t.check('periodsAvailable: years and months with live rows, newest first, no duplicates', () => {
    const rows = [row({ date: '2026-11-01' }), row({ date: '2026-11-30' }), row({ date: '2025-12-31' }), row({ date: '2026-02-09T10:00:00' }), row({ date: '2027-01-01', deleted: true }), row({ date: null })];
    t.eq(periodsAvailable(rows), { years: [2026, 2025], months: ['2026-11', '2026-02', '2025-12'] });
    t.eq(periodsAvailable([]), { years: [], months: [] });
  });

  t.check('periodOptions: periods with data plus the current one, newest first', () => {
    const rows = [row({ date: '2026-10-05' }), row({ date: '2026-11-05' }), row({ date: '2025-12-05' })];
    t.eq(periodOptions(rows, 'month', '2026-11-24').map(periodKey), ['2026-11', '2026-10', '2025-12']);
    t.eq(periodOptions(rows, 'month', '2027-02-03').map(periodKey), ['2027-02', '2026-11', '2026-10', '2025-12']);
    t.eq(periodOptions(rows, 'year', '2026-11-24').map(periodKey), ['2026', '2025']);
    t.eq(periodOptions(rows, 'year', '2027-01-01').map(periodKey), ['2027', '2026', '2025']);
    t.eq(periodOptions([], 'month', '2026-11-24').map(periodKey), ['2026-11']);
    t.eq(periodOptions(rows, 'month', 'not a date').map(periodKey), ['2026-11', '2026-10', '2025-12']);
  });

  // totals must equal the CSV export -----------------------------------------------------------------

  const filters = {
    'everything': (rows) => rows,
    'November 2026': (rows) => inPeriod(rows, MONTH(2026, 11)),
    'the year 2026': (rows) => inPeriod(rows, YEAR(2026)),
    'February 2028': (rows) => inPeriod(rows, MONTH(2028, 2)),
    'business only': (rows) => rows.filter((r) => r.is_business),
    'one member': (rows) => rows.filter((r) => r.owner_id === ANA),
    'nothing': () => [],
  };

  t.check('tiles equal csvTotals for hand-made and random data under every filter', () => {
    const sets = [phoneGroupRows(), randomRows(3, 80), randomRows(11, 400), []];
    for (const [setIndex, rows] of sets.entries()) {
      for (const [name, filter] of Object.entries(filters)) {
        const picked = filter(rows);
        const s = summarize(picked);
        const c = csvTotals(picked);
        t.eq({ spent: s.spent, income: s.income, net: s.net, vat: s.vat }, c, `set ${setIndex}, filter ${name}`);
      }
    }
  });

  t.check('tiles equal csvTotals when the rows still include deleted ones (both ignore them)', () => {
    const rows = randomRows(5, 300).map((r, i) => (i % 7 === 0 ? { ...r, deleted: true } : r));
    const s = summarize(rows);
    t.eq({ spent: s.spent, income: s.income, net: s.net, vat: s.vat }, csvTotals(rows));
  });

  t.check('tiles equal the TOTAL rows written into the CSV file', () => {
    const rows = randomRows(9, 120);
    const picked = inPeriod(rows, YEAR(2026));
    const csv = buildCsv(picked, { members: MEMBERS, wallets: [] });
    const amountOf = (label) => {
      const line = csv.split('\r\n').find((l) => l.includes(`,TOTAL,`) && l.includes(label));
      t.ok(line, `no total row for ${label}`);
      const cells = line.split(',');
      return cells[CSV_COLUMNS.indexOf('amount')] || cells[CSV_COLUMNS.indexOf('vat')]; // the VAT total sits in the vat column
    };
    const s = summarize(picked);
    t.eq(amountOf('Total spent'), pesosPlain(s.spent));
    t.eq(amountOf('Total income'), pesosPlain(s.income));
    t.eq(amountOf('Net (income minus spent)'), pesosPlain(s.net));
    t.eq(amountOf('VAT on expenses'), pesosPlain(s.vat));
  });

  // charts: scale helpers ---------------------------------------------------------------------------

  t.check('niceScale: whole-peso ticks from 0 up to a round top that holds the data', () => {
    t.eq(niceScale(12345), { top: 15000, ticks: [0, 5000, 10000, 15000] });
    t.eq(niceScale(20000), { top: 20000, ticks: [0, 5000, 10000, 15000, 20000] });
    t.eq(niceScale(0), { top: 100, ticks: [0, 100] });
    t.eq(niceScale(50), { top: 100, ticks: [0, 100] });
    for (const max of [1, 99, 100, 101, 999, 123456, 98765432, 100000000]) {
      const { top, ticks } = niceScale(max);
      t.ok(top >= max, `top ${top} holds ${max}`);
      t.ok(ticks.length >= 2 && ticks.length <= 7, `${ticks.length} ticks for ${max}`);
      t.ok(ticks.every((x) => x % 100 === 0), 'whole pesos');
    }
  });

  t.check('percentText: one decimal, empty without a total', () => {
    t.eq(percentText(1, 3), '33.3%');
    t.eq(percentText(5, 5), '100.0%');
    t.eq(percentText(1, 0), '');
  });

  // dashboard rendering (needs a DOM: runs in the browser page and under jsdom) --------------------

  if (typeof document === 'undefined') return;

  const TODAY = '2026-11-24';
  const HOSTILE = '<img src=x onerror="window.__pwned=1">';

  function mount() {
    const root = document.createElement('div');
    document.body.append(root);
    return root;
  }
  const personalCtx = (over = {}) => ({
    mode: 'personal', meId: ME, groupName: '', today: TODAY,
    members: [{ id: ME, name: 'Matt' }],
    wallets: [
      { id: 'w-me', owner_id: ME, name: 'Cash', type: 'cash', opening_balance: 100000, deleted: false },
      { id: 'w-gc', owner_id: ME, name: HOSTILE, type: 'gcash', opening_balance: 0, deleted: false },
      { id: 'w-ana', owner_id: ANA, name: 'Ana cash', type: 'cash', opening_balance: 5, deleted: false },
    ],
    txns: [
      row({ owner_id: ME, amount: 12345, vat: 1322, receipt_path: 'me/1.jpg', merchant: HOSTILE, date: '2026-11-02' }),
      row({ owner_id: ME, amount: 5000, category: 'transport', date: '2026-11-20' }),
      row({ owner_id: ME, amount: 250000, type: 'income', category: 'salary', date: '2026-11-01' }),
      row({ owner_id: ME, amount: 800, date: '2026-10-31' }),
      row({ owner_id: ME, amount: 3000, wallet_id: 'w-me', type: 'transfer', to_wallet_id: 'w-gc', date: '2026-11-03', category: null }),
      row({ owner_id: ANA, amount: 777777, date: '2026-11-04' }), // someone else's row must not leak into a personal view
    ],
    ...over,
  });
  const groupCtx = (over = {}) => ({
    mode: 'group', meId: ME, groupName: HOSTILE, today: TODAY,
    members: [{ id: ANA, name: 'Ana' }, { id: BEN, name: HOSTILE }, { id: ME, name: 'Matt' }],
    wallets: [
      { id: 'w-me', owner_id: ME, name: 'Cash', type: 'cash', opening_balance: 1000, deleted: false },
      { id: 'w-ana', owner_id: ANA, name: 'GCash', type: 'gcash', opening_balance: 2000, deleted: false },
    ],
    txns: phoneGroupRows().concat([row({ owner_id: 'user-stranger', amount: 999999 })]),
    ...over,
  });
  const text = (root, selector) => [...root.querySelectorAll(selector)].map((n) => n.textContent);
  const click = (root, selector) => root.querySelector(selector).dispatchEvent(new window.MouseEvent('click', { bubbles: true }));

  t.check('dashboard: personal tiles show this month, using only my rows, as formatPesos text', () => {
    resetDashboardPeriod();
    const root = mount();
    renderDashboard(root, personalCtx());
    const tiles = [...root.querySelectorAll('.dash-tile')].map((n) => [n.querySelector('dt').textContent, n.querySelector('.dash-tile-value').textContent]);
    t.eq(tiles, [
      ['Spent', formatPesos(17345)], ['Income', formatPesos(250000)], ['Net', `+${formatPesos(232655)}`],
      ['VAT paid (from receipts)', formatPesos(1322)], ['Receipts', '1'],
    ]);
    t.ok(!root.textContent.includes('7,777.77'), 'another member\'s row leaked into the personal view');
  });

  t.check('dashboard: tile totals equal csvTotals for the same filter (month and year)', () => {
    resetDashboardPeriod();
    const ctx = personalCtx();
    const mine = ctx.txns.filter((r) => r.owner_id === ME);
    const root = mount();
    renderDashboard(root, ctx);
    const c = csvTotals(inPeriod(mine, MONTH(2026, 11)));
    t.eq(text(root, '.dash-tile-value').slice(0, 2), [formatPesos(c.spent), formatPesos(c.income)]);
    click(root, '[data-dash-focus="kind-year"]');
    const y = csvTotals(inPeriod(mine, YEAR(2026)));
    t.eq(text(root, '.dash-tile-value').slice(0, 2), [formatPesos(y.spent), formatPesos(y.income)]);
    resetDashboardPeriod();
  });

  t.check('dashboard: hostile names and merchants stay text; nothing is injected or run', () => {
    resetDashboardPeriod();
    const root = mount();
    renderDashboard(root, personalCtx());
    t.eq(root.querySelectorAll('img').length, 0);
    t.ok(root.textContent.includes(HOSTILE), 'the wallet name is shown as plain text');
    t.ok(!globalThis.window || !window.__pwned, 'a handler ran');
    renderDashboard(root, groupCtx());
    t.eq(root.querySelectorAll('img').length, 0);
    t.ok(root.textContent.includes(HOSTILE));
    for (const m of root.querySelectorAll('.dash-mark')) t.ok(m.getAttribute('aria-label').length > 0);
    resetDashboardPeriod();
  });

  t.check('dashboard: no wording claims anything is deductible (RULES 22)', () => {
    resetDashboardPeriod();
    const root = mount();
    renderDashboard(root, personalCtx());
    renderDashboard(root, groupCtx());
    t.ok(!/deduct/i.test(root.textContent));
    resetDashboardPeriod();
  });

  t.check('dashboard: an empty period shows the empty state, not blank charts, and offers the latest period', () => {
    resetDashboardPeriod();
    const root = mount();
    renderDashboard(root, personalCtx({ today: '2027-03-09', txns: personalCtx().txns }));
    t.eq(root.querySelectorAll('.dash-empty').length, 1);
    t.ok(root.querySelector('.dash-empty').textContent.includes('March 2027'));
    t.eq(root.querySelectorAll('.dash-tiles, .dash-svg').length, 0);
    t.ok(root.querySelector('.dash-wallets'), 'wallet balances still show');
    const go = root.querySelector('.dash-empty button');
    t.ok(go && go.textContent === 'Show November 2026');
    go.dispatchEvent(new window.MouseEvent('click', { bubbles: true }));
    t.eq(root.querySelectorAll('.dash-empty').length, 0);
    t.eq(root.querySelectorAll('.dash-tile').length, 5);
    resetDashboardPeriod();
  });

  t.check('dashboard: no data at all is an empty state with no button to nowhere', () => {
    resetDashboardPeriod();
    const root = mount();
    renderDashboard(root, personalCtx({ txns: [], wallets: [] }));
    t.eq(root.querySelectorAll('.dash-empty').length, 1);
    t.eq(root.querySelectorAll('.dash-empty button').length, 0);
    t.eq(root.querySelectorAll('.dash-svg').length, 0);
    resetDashboardPeriod();
  });

  t.check('dashboard: every chart mark is keyboard focusable with a native title, an aria-label and a role', () => {
    resetDashboardPeriod();
    const root = mount();
    renderDashboard(root, groupCtx({ txns: [...phoneGroupRows(), row({ owner_id: BEN, amount: 4200, date: '2026-03-04', category: 'bills' })] }));
    const marks = [...root.querySelectorAll('.dash-mark')];
    t.ok(marks.length >= 12 + 12 + 3, `only ${marks.length} marks`);
    for (const m of marks) {
      t.eq(m.getAttribute('tabindex'), '0');
      t.eq(m.getAttribute('role'), 'img');
      t.eq(m.querySelector('title').textContent, m.getAttribute('aria-label'));
    }
    for (const svg of root.querySelectorAll('svg.dash-svg')) t.ok(svg.getAttribute('aria-label'), 'chart has a text summary');
    for (const d of root.querySelectorAll('.dash-card')) {
      if (d.querySelector('.dash-svg')) t.ok(d.querySelector('table, .dash-table'), 'chart has a table twin');
    }
    resetDashboardPeriod();
  });

  t.check('dashboard: the tooltip shows on focus and hover with the same words, hides on blur and Escape', () => {
    resetDashboardPeriod();
    const root = mount();
    renderDashboard(root, personalCtx());
    const mark = root.querySelector('.dash-mark');
    const tip = mark.closest('.dash-plot').querySelector('.dash-tip');
    t.ok(tip.hidden, 'starts hidden');
    mark.focus();
    t.ok(!tip.hidden, 'focus opens it');
    t.ok(tip.textContent.includes(mark.getAttribute('aria-label').split(': ')[0]));
    mark.blur();
    t.ok(tip.hidden, 'blur closes it');
    mark.dispatchEvent(new window.MouseEvent('mouseenter'));
    t.ok(!tip.hidden, 'hover opens it');
    mark.dispatchEvent(new window.KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));
    t.ok(tip.hidden, 'Escape closes it');
    resetDashboardPeriod();
  });

  t.check('dashboard: rendering twice gives the same page (idempotent) and keeps the period choice', () => {
    resetDashboardPeriod();
    const root = mount();
    const ctx = personalCtx();
    renderDashboard(root, ctx);
    const first = root.innerHTML;
    renderDashboard(root, ctx);
    t.eq(root.innerHTML === first, true);
    click(root, '[data-dash-focus="prev"]'); // October 2026
    const picked = root.querySelector('.dash-select').value;
    t.eq(picked, '2026-10');
    renderDashboard(root, ctx); // a data refresh
    t.eq(root.querySelector('.dash-select').value, '2026-10');
    const other = mount();
    renderDashboard(other, ctx); // a new root, the tab shown again
    t.eq(other.querySelector('.dash-select').value, '2026-10');
    t.eq(other.querySelectorAll('.dash').length, 1);
    resetDashboardPeriod();
  });

  t.check('dashboard: month/year toggle, earlier/later buttons and the select all move the period', () => {
    resetDashboardPeriod();
    const root = mount();
    const ctx = personalCtx();
    renderDashboard(root, ctx);
    t.eq(root.querySelector('[data-dash-focus="next"]').disabled, true); // already at the newest period
    click(root, '[data-dash-focus="kind-year"]');
    t.eq(root.querySelector('.dash-select').value, '2026');
    t.eq(root.querySelector('[data-dash-focus="kind-year"]').getAttribute('aria-pressed'), 'true');
    t.eq(root.querySelector('[data-dash-focus="kind-month"]').getAttribute('aria-pressed'), 'false');
    click(root, '[data-dash-focus="kind-month"]');
    t.eq(root.querySelector('.dash-select').value, '2026-11');
    const select = root.querySelector('.dash-select');
    select.value = '2026-10';
    select.dispatchEvent(new window.Event('change', { bubbles: true }));
    t.eq(root.querySelector('.dash-select').value, '2026-10');
    t.eq(text(root, '.dash-tile-value')[0], formatPesos(800));
    click(root, '[data-dash-focus="next"]');
    t.eq(root.querySelector('.dash-select').value, '2026-11');
    resetDashboardPeriod();
  });

  t.check('dashboard: month mode highlights the selected month on the monthly chart; year mode highlights none', () => {
    resetDashboardPeriod();
    const root = mount();
    renderDashboard(root, personalCtx());
    t.eq(root.querySelectorAll('.dash-selected').length, 1);
    t.eq(root.querySelectorAll('.dash-tick-on').length, 1);
    t.eq(root.querySelector('.dash-tick-on').textContent, 'Nov');
    t.ok(root.querySelectorAll('.dash-dim').length >= 1, 'the other months are de-emphasised');
    click(root, '[data-dash-focus="kind-year"]');
    t.eq(root.querySelectorAll('.dash-selected').length, 0);
    t.eq(root.querySelectorAll('.dash-dim').length, 0);
    resetDashboardPeriod();
  });

  t.check('dashboard: group view compares members (spent, income, top category) with the phone numbers', () => {
    resetDashboardPeriod();
    const root = mount();
    renderDashboard(root, groupCtx());
    const rows = [...root.querySelectorAll('.dash-card')].find((c) => c.querySelector('h3').textContent === 'Members compared').querySelectorAll('tbody tr');
    const cells = [...rows].map((r) => [...r.children].map((c) => c.textContent));
    t.eq(cells.map((c) => [c[0], c[1], c[2]]), [
      ['Matt (you)', formatPesos(100000), formatPesos(0)],
      ['Ana', formatPesos(13400), formatPesos(500000)],
      [HOSTILE, formatPesos(0), formatPesos(0)],
    ]);
    t.eq(cells[1][3], `Food & Dining, ${formatPesos(13400)}`);
    t.eq(cells[2][3], 'No spending');
    t.ok(!root.textContent.includes('9,999.99'), 'a stranger\'s row leaked into the group');
    resetDashboardPeriod();
  });

  t.check('dashboard: group view has the stacked monthly chart with a legend, numbers and wallets grouped by member', () => {
    resetDashboardPeriod();
    const root = mount();
    renderDashboard(root, groupCtx());
    t.eq(root.querySelectorAll('.dash-legend li').length, 3);
    t.eq(text(root, '.dash-legend .dash-chip'), ['1', '2', '3']); // a number as well as a color
    t.eq(text(root, '.dash-wallet-owner'), ['Ana', 'Matt (you)']);
    t.ok(root.querySelector('.dash-title').textContent.startsWith(HOSTILE));
    t.eq(root.querySelectorAll('.dash-segtag').length >= 1, true);
    resetDashboardPeriod();
  });

  t.check('dashboard: member colors follow the member, not the row order', () => {
    resetDashboardPeriod();
    const slots = (members) => {
      const root = mount();
      renderDashboard(root, groupCtx({ members }));
      return Object.fromEntries([...root.querySelectorAll('.dash-legend li')].map((li) => [li.lastChild.textContent, li.firstChild.className]));
    };
    const a = slots([{ id: ANA, name: 'Ana' }, { id: BEN, name: 'Ben' }, { id: ME, name: 'Matt' }]);
    const b = slots([{ id: ME, name: 'Matt' }, { id: BEN, name: 'Ben' }, { id: ANA, name: 'Ana' }]);
    t.eq(a, b);
    resetDashboardPeriod();
  });

  t.check('dashboard: more than 8 members fold the tail into one gray "Other members" series', () => {
    resetDashboardPeriod();
    const members = Array.from({ length: 10 }, (_, i) => ({ id: `u${String(i).padStart(2, '0')}`, name: `Member ${i}` }));
    const txns = members.map((m, i) => row({ owner_id: m.id, amount: 1000 * (i + 1) }));
    const root = mount();
    renderDashboard(root, groupCtx({ members, txns, wallets: [] }));
    const names = text(root, '.dash-legend li span:last-child');
    t.eq(names.length, 8);
    t.eq(names[7], 'Other members');
    t.eq(root.querySelectorAll('.dash-legend .dash-sother').length, 1);
    t.eq(root.querySelectorAll('.dash-legend .dash-s9').length, 0);
    resetDashboardPeriod();
  });

  t.check('dashboard: a group with no members says so instead of drawing empty charts', () => {
    resetDashboardPeriod();
    const root = mount();
    renderDashboard(root, groupCtx({ members: [] }));
    t.eq(root.querySelectorAll('.dash-svg').length, 0);
    t.eq(root.querySelectorAll('.dash-empty').length, 1);
    resetDashboardPeriod();
  });

  t.check('dashboard: wallet balances use all time, not the picked period, and show a negative with a minus sign', () => {
    resetDashboardPeriod();
    const root = mount();
    const ctx = personalCtx({ wallets: [{ id: 'w-me', owner_id: ME, name: 'Cash', type: 'cash', opening_balance: 100, deleted: false }], txns: [row({ owner_id: ME, amount: 5000, date: '2019-05-05' }), row({ owner_id: ME, amount: 1, date: '2026-11-05' })] });
    renderDashboard(root, ctx);
    t.eq(text(root, '.dash-wallet-balance'), [formatPesos(100 - 5000 - 1)]);
    t.ok(text(root, '.dash-wallet-balance')[0].startsWith('-'));
    resetDashboardPeriod();
  });

  t.check('dashboard: an income-only period still renders and says there is no spending', () => {
    resetDashboardPeriod();
    const root = mount();
    renderDashboard(root, personalCtx({ txns: [row({ owner_id: ME, type: 'income', category: 'salary', amount: 9900, date: '2026-11-02' })] }));
    t.eq(root.querySelectorAll('.dash-tile').length, 5);
    t.eq(root.querySelectorAll('.dash-svg').length, 0);
    t.ok(root.querySelectorAll('.dash-none').length >= 2);
    resetDashboardPeriod();
  });
}
