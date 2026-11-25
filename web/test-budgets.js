// Checks for budgets.js, the budget calls in data.js and the Budgets card of the dashboard (RFC-009).
// Runs in the browser (test.html) and in Node (node web/test-node.mjs); the card checks need a DOM, so they
// only run when a `document` exists (the browser page, or jsdom), like the dashboard checks in test-stats.js.
import {
  LEVEL, budgetSpent, percentOf, levelOf, barFraction, budgetUsage, usageList, visibleBudgets, budgetMonth,
  checkBudget, budgetTitle, budgetSummary, budgetStatus,
} from './budgets.js';
import {
  fetchAllPages, loadBudgets, normalizeBudget, createBudget, updateOwnBudget, tombstoneBudget, updateOwnTxn,
} from './data.js';
import { renderDashboard, resetDashboardPeriod } from './dashboard.js';

// --- the parity vector: keep in sync with BudgetsTest.kt ----------------------------------------------------
// month = "2026-11". me = "me". Group "g1" members: me, kim. "zed" is not in g1. Amounts are centavos.
const MONTH = '2026-11';
const ME = 'me';
const KIM = 'kim';
const ZED = 'zed';
const G1_MEMBERS = [ME, KIM];

const tx = (id, owner, category, date, amount, extra = {}) => ({
  id, owner_id: owner, type: 'expense', category, date, amount, deleted: false, ...extra,
});
const PARITY_TXNS = [
  tx('t1', ME, 'food', '2026-11-03', 30000),
  tx('t2', ME, 'food', '2026-11-20', 20000),
  tx('t3', ME, 'groceries', '2026-11-05', 50000),
  tx('t4', ME, null, '2026-11-06', 10000),
  tx('t5', ME, 'xyz', '2026-11-07', 5000),
  tx('t6', ME, 'food', '2026-10-31', 99900), // other month
  tx('t7', ME, 'salary', '2026-11-04', 1000000, { type: 'income' }),
  tx('t8', ME, null, '2026-11-08', 70000, { type: 'transfer' }),
  tx('t9', ME, 'food', '2026-11-09', 40000, { deleted: true }),
  tx('t10', KIM, 'food', '2026-11-10', 25000),
  tx('t11', KIM, 'groceries', '2026-11-11', 15000),
  tx('t12', ZED, 'food', '2026-11-12', 99000),
];
const bud = (id, group, category, limit, owner = ME) => ({
  id, owner_id: owner, group_id: group, category, monthly_limit: limit, updated_at: 1, deleted: false,
});
// [budget, spent, percent, level]. The phone-only inbox case is not here: inbox rows never reach the web.
const PARITY = [
  [bud('b1', null, null, 200000), 115000, 57, 'OK'],
  [bud('b2', null, 'food', 60000), 50000, 83, 'WARN'],
  [bud('b3', null, 'other', 15000), 15000, 100, 'OVER'],
  [bud('b4', 'g1', 'food', 100000), 75000, 75, 'OK'], // me 50000 + kim 25000
  [bud('b5', 'g1', null, 150000), 155000, 103, 'OVER'], // me 115000 + kim 40000
  [bud('b6', null, 'groceries', 40000), 50000, 125, 'OVER'],
];

// A budget of mine with a given limit and a single expense of `spent`, for the boundary checks.
const usageFor = (limit, spent) => budgetUsage(bud('x', null, null, limit), [tx('s', ME, 'food', '2026-11-02', spent)], MONTH, []);

// A tiny stand-in for the supabase-js query builder: select / insert / update with eq, is, order, range.
// `writeRows(q)` answers an insert or update; q.eqs lists the eq()/is() filters the call used.
function fakeClient({ tables = {}, failOn = null, writeRows = null } = {}) {
  const calls = [];
  return {
    calls,
    from(table) {
      const q = { table, op: 'select', payload: null, eqs: [], filters: [], order: [], range: null };
      const builder = {
        select() { return builder; },
        insert(p) { q.op = 'insert'; q.payload = p; return builder; },
        update(p) { q.op = 'update'; q.payload = p; return builder; },
        eq(col, val) { q.eqs.push([col, val]); q.filters.push((r) => r[col] === val); return builder; },
        is(col, val) { q.eqs.push([col, val]); q.filters.push((r) => (r[col] ?? null) === val); return builder; },
        order(col) { q.order.push(col); return builder; },
        range(a, b) { q.range = [a, b]; return builder; },
        then(resolve, reject) {
          calls.push(q);
          let result;
          if (failOn && failOn(q)) result = { data: null, error: { message: 'boom' } };
          else if (q.op !== 'select') result = { data: writeRows ? writeRows(q) : [], error: null };
          else {
            let rows = (tables[table] || []).filter((r) => q.filters.every((f) => f(r)));
            for (const col of [...q.order].reverse()) rows = [...rows].sort((a, b) => (a[col] < b[col] ? -1 : a[col] > b[col] ? 1 : 0));
            if (q.range) rows = rows.slice(q.range[0], q.range[1] + 1);
            result = { data: rows, error: null };
          }
          return Promise.resolve(result).then(resolve, reject);
        },
      };
      return builder;
    },
  };
}

export function tests(t) {
  // ------------------------------------------------------------ usage math: the parity vector
  t.check('budgets: parity vector b1..b6 gives the same spent, percent and level as the phone', () => {
    for (const [budget, spent, percent, level] of PARITY) {
      const u = budgetUsage(budget, PARITY_TXNS, MONTH, G1_MEMBERS);
      t.eq([budget.id, u.spent, u.percent, u.level], [budget.id, spent, percent, level]);
    }
  });
  t.check('budgets: usageList keeps the order it is given and matches budgetUsage', () => {
    const list = usageList(PARITY.map((p) => p[0]), PARITY_TXNS, MONTH, G1_MEMBERS);
    t.eq(list.map((u) => u.budget.id), ['b1', 'b2', 'b3', 'b4', 'b5', 'b6']);
    t.eq(list.map((u) => u.spent), PARITY.map((p) => p[1]));
    t.eq(usageList(null, PARITY_TXNS, MONTH, []), []);
  });

  // ------------------------------------------------------------ percent and level: floor, not round
  t.check('budgets: percent is floored (79.9% is 79 and not WARN, 99.9% is 99 and not OVER)', () => {
    t.eq(percentOf(799, 1000), 79);
    t.eq(levelOf(799, 1000), LEVEL.OK);
    t.eq(percentOf(7999, 10000), 79);
    t.eq(levelOf(7999, 10000), LEVEL.OK);
    t.eq(percentOf(999, 1000), 99);
    t.eq(levelOf(999, 1000), LEVEL.WARN);
    t.eq(percentOf(1, 3), 33);
    t.eq(percentOf(2, 3), 66);
  });
  t.check('budgets: exactly 80% is WARN and exactly 100% is OVER', () => {
    t.eq([percentOf(800, 1000), levelOf(800, 1000)], [80, LEVEL.WARN]);
    t.eq([percentOf(1000, 1000), levelOf(1000, 1000)], [100, LEVEL.OVER]);
    t.eq([percentOf(1001, 1000), levelOf(1001, 1000)], [100, LEVEL.OVER]);
    t.eq([percentOf(0, 1000), levelOf(0, 1000)], [0, LEVEL.OK]);
  });
  t.check('budgets: percent is exact for the largest limits money.js accepts (just under 80% stays 79)', () => {
    // 159_999_999_999_999 * 100 is above Number.MAX_SAFE_INTEGER, so this guards the integer arithmetic.
    t.eq(percentOf(159999999999999, 200000000000000), 79);
    t.eq(levelOf(159999999999999, 200000000000000), LEVEL.OK);
    t.eq(percentOf(160000000000000, 200000000000000), 80);
  });
  t.check('budgets: a limit of 0 or less means unset: 0%, OK, empty bar, even with spending', () => {
    for (const limit of [0, -500]) {
      const u = usageFor(limit, 12345);
      t.eq([u.spent, u.percent, u.level, u.barFraction], [12345, 0, LEVEL.OK, 0]);
      t.eq(budgetStatus(u), 'No limit set');
    }
    t.eq(percentOf(5, 0), 0);
    t.eq(levelOf(5, 0), LEVEL.OK);
    t.eq(barFraction(5, 0), 0);
  });
  t.check('budgets: the bar clamps at 100% while the percent and remaining keep the truth', () => {
    const over = usageFor(40000, 50000);
    t.eq([over.percent, over.barFraction, over.remaining], [125, 1, -10000]);
    t.eq(usageFor(200000, 115000).barFraction, 0.575);
    t.eq(barFraction(-5, 100), 0);
    t.eq(usageFor(1000, 250).remaining, 750);
  });

  // ------------------------------------------------------------ what counts
  t.check('budgets: month boundaries compare the date text: 31 Oct and 1 Dec are out, 1 and 30 Nov are in', () => {
    const rows = [
      tx('a', ME, 'food', '2026-10-31', 1), tx('b', ME, 'food', '2026-11-01', 10), tx('c', ME, 'food', '2026-11-30', 100),
      tx('d', ME, 'food', '2026-12-01', 1000), tx('e', ME, 'food', '2026-11-30T23:59:59', 10000), tx('f', ME, 'food', '2025-11-15', 100000),
    ];
    t.eq(budgetSpent(bud('x', null, null, 1), rows, MONTH, []), 10110);
  });
  t.check('budgets: rows without a usable date are ignored', () => {
    const rows = [tx('a', ME, 'food', null, 500), tx('b', ME, 'food', undefined, 500), tx('c', ME, 'food', '', 500), tx('d', ME, 'food', 20261103, 500)];
    t.eq(budgetSpent(bud('x', null, null, 1), rows, MONTH, []), 0);
  });
  t.check('budgets: deleted rows are ignored; only a literal deleted = true counts as deleted', () => {
    const rows = [tx('a', ME, 'food', '2026-11-02', 100, { deleted: true }), tx('b', ME, 'food', '2026-11-02', 10, { deleted: false }), tx('c', ME, 'food', '2026-11-02', 1, { deleted: undefined })];
    t.eq(budgetSpent(bud('x', null, null, 1), rows, MONTH, []), 11);
  });
  t.check('budgets: only expenses count; income and transfers do not', () => {
    const rows = [
      tx('a', ME, 'food', '2026-11-02', 100), tx('b', ME, 'salary', '2026-11-02', 5000, { type: 'income' }),
      tx('c', ME, null, '2026-11-02', 7000, { type: 'transfer' }),
    ];
    t.eq(budgetSpent(bud('x', null, null, 1), rows, MONTH, []), 100);
  });
  t.check('budgets: a personal budget counts only its owner, whatever memberIds says', () => {
    t.eq(budgetSpent(bud('p', null, 'food', 1), PARITY_TXNS, MONTH, [ME, KIM, ZED]), 50000);
    t.eq(budgetSpent(bud('p', null, 'food', 1, KIM), PARITY_TXNS, MONTH, [ME, KIM]), 25000);
  });
  t.check('budgets: a group budget sums its members only; a stranger or a missing member list adds nothing', () => {
    const g = bud('g', 'g1', 'food', 1);
    t.eq(budgetSpent(g, PARITY_TXNS, MONTH, [ME, KIM]), 75000);
    t.eq(budgetSpent(g, PARITY_TXNS, MONTH, [ME, KIM, ZED]), 174000); // zed counts only if the caller says zed is a member
    t.eq(budgetSpent(g, PARITY_TXNS, MONTH, [KIM]), 25000);
    t.eq(budgetSpent(g, PARITY_TXNS, MONTH, []), 0);
    t.eq(budgetSpent(g, PARITY_TXNS, MONTH, undefined), 0);
  });
  t.check('budgets: the other bucket takes null, empty and unknown categories and the literal other slug', () => {
    const rows = [
      tx('a', ME, null, '2026-11-02', 1), tx('b', ME, 'xyz', '2026-11-02', 10), tx('c', ME, '', '2026-11-02', 100),
      tx('d', ME, 'other', '2026-11-02', 1000), tx('e', ME, 'food', '2026-11-02', 10000), tx('f', ME, 'salary', '2026-11-02', 100000),
    ];
    t.eq(budgetSpent(bud('o', null, 'other', 1), rows, MONTH, []), 101111); // an expense filed under an income slug is also "other"
    t.eq(budgetSpent(bud('o', null, 'food', 1), rows, MONTH, []), 10000);
    t.eq(budgetSpent(bud('o', null, null, 1), rows, MONTH, []), 111111);
  });
  t.check('budgets: a budget for a category nobody can log counts nothing; amounts given as text still add up', () => {
    t.eq(budgetSpent(bud('z', null, 'xyz', 1), PARITY_TXNS, MONTH, []), 0);
    const rows = [tx('a', ME, 'food', '2026-11-02', '250'), tx('b', ME, 'food', '2026-11-02', null)];
    t.eq(budgetSpent(bud('x', null, 'food', 1), rows, MONTH, []), 250);
  });

  // ------------------------------------------------------------ which budgets show, in what order
  const MIXED = [
    bud('mine-food', null, 'food', 1), bud('mine-all', null, null, 1), bud('mine-groc', null, 'groceries', 1), bud('mine-bills', null, 'bills', 1),
    bud('kim-personal', null, 'food', 1, KIM),
    bud('g1-food', 'g1', 'food', 1), bud('g1-all', 'g1', null, 1), bud('g1-kim', 'g1', 'travel', 1, KIM),
    bud('g2-all', 'g2', null, 1),
    { ...bud('gone', null, 'health', 1), deleted: true }, { ...bud('g1-gone', 'g1', 'health', 1), deleted: true },
  ];
  t.check('visibleBudgets: Personal shows only my budgets with no group, overall first then by label', () => {
    t.eq(visibleBudgets(MIXED, { mode: 'personal', groupId: null }, ME).map((b) => b.id), ['mine-all', 'mine-bills', 'mine-food', 'mine-groc']);
  });
  t.check('visibleBudgets: Group shows every member\'s budget of that group only, never another group or a personal one', () => {
    t.eq(visibleBudgets(MIXED, { mode: 'group', groupId: 'g1' }, ME).map((b) => b.id), ['g1-all', 'g1-food', 'g1-kim']);
    t.eq(visibleBudgets(MIXED, { mode: 'group', groupId: 'g2' }, ME).map((b) => b.id), ['g2-all']);
    t.eq(visibleBudgets(MIXED, { mode: 'group', groupId: 'nope' }, ME), []);
    t.eq(visibleBudgets(null, { mode: 'personal' }, ME), []);
  });
  t.check('visibleBudgets: my personal budgets and another member\'s personal ones are told apart by owner', () => {
    t.eq(visibleBudgets(MIXED, { mode: 'personal', groupId: null }, KIM).map((b) => b.id), ['kim-personal']);
  });
  t.check('visibleBudgets: the list it is given is not reordered in place', () => {
    const copy = MIXED.map((b) => b.id);
    visibleBudgets(MIXED, { mode: 'personal', groupId: null }, ME);
    t.eq(MIXED.map((b) => b.id), copy);
  });

  // ------------------------------------------------------------ the month a period shows
  t.check('budgetMonth: a month period is that month, with no note', () => {
    t.eq(budgetMonth({ kind: 'month', year: 2026, month: 3 }, '2026-11-24'), { month: '2026-03', label: 'March 2026', note: null });
  });
  t.check('budgetMonth: a year view shows the current month in the current year and December otherwise, and says so', () => {
    const now = budgetMonth({ kind: 'year', year: 2026 }, '2026-11-24');
    t.eq([now.month, now.label], ['2026-11', 'November 2026']);
    t.ok(now.note.includes('November 2026') && now.note.includes('current month'), now.note);
    const past = budgetMonth({ kind: 'year', year: 2025 }, '2026-11-24');
    t.eq([past.month, past.label], ['2025-12', 'December 2025']);
    t.ok(past.note.includes('December 2025'), past.note);
    t.eq(budgetMonth({ kind: 'year', year: 2027 }, '2026-11-24').month, '2027-12');
    t.eq(budgetMonth({ kind: 'year', year: 2026 }, '2026-01-02').month, '2026-01');
  });

  // ------------------------------------------------------------ validation
  const listed = [bud('e-all', null, null, 100), bud('e-food', null, 'food', 100), bud('e-g', 'g1', 'travel', 100), bud('k-g-food', 'g1', 'food', 100, KIM),
    { ...bud('e-gone', null, 'health', 100), deleted: true }];
  const draft = (over = {}) => ({ me: ME, groupId: null, category: 'groceries', limit: '5,000', editingId: null, ...over });
  const check = (over) => checkBudget(draft(over), listed, ['g1']);
  t.check('checkBudget: a good draft gives centavos from the strict parser and no errors', () => {
    t.eq(check({}), { errors: {}, budget: { groupId: null, category: 'groceries', monthlyLimit: 500000 } });
    t.eq(check({ limit: '1,234.50' }).budget.monthlyLimit, 123450);
    t.eq(check({ limit: '₱99' }).budget.monthlyLimit, 9900);
    t.eq(check({ limit: '.5' }).budget.monthlyLimit, 50);
    for (const category of ['', null, undefined]) t.eq(checkBudget(draft({ category }), [], ['g1']).budget.category, null);
  });
  t.check('checkBudget: the limit must be a plain amount above zero', () => {
    for (const limit of ['', '  ', 'abc', '0', '0.00', '-5', '1.234', '1,23', '1..5', '5 000', undefined, null, 5000]) {
      const r = check({ limit });
      t.ok(r.errors.limit && r.budget === null, `limit ${JSON.stringify(limit)} should be refused`);
    }
    t.ok(check({ limit: '0' }).errors.limit.includes('more than'));
    t.ok(check({ limit: 'abc' }).errors.limit.includes('Enter a limit'));
    t.eq(check({ limit: '0.01' }).budget.monthlyLimit, 1);
  });
  t.check('checkBudget: the category must be Overall or an expense category', () => {
    t.ok(check({ category: 'salary' }).errors.category, 'an income category is refused');
    t.ok(check({ category: 'xyz' }).errors.category);
    t.eq(check({ category: 'other' }).errors, {});
  });
  t.check('checkBudget: a second budget for the same owner, group and category is refused with a clear message', () => {
    const r = check({ category: 'food' });
    t.ok(r.errors.category && r.errors.category.includes('Food & Dining') && r.errors.category.includes('Edit that one'), r.errors.category);
    t.eq(r.budget, null);
    t.ok(check({ category: '' }).errors.category.includes('an overall'));
    t.ok(check({ groupId: 'g1', category: 'travel' }).errors.category.includes('in this group'));
  });
  t.check('checkBudget: editing ignores the budget being edited, but not a different one', () => {
    t.eq(check({ category: 'food', editingId: 'e-food' }).errors, {});
    t.ok(check({ category: 'food', editingId: 'e-all' }).errors.category);
  });
  t.check('checkBudget: the same category in another scope, another owner\'s budget, or a deleted one is not a duplicate', () => {
    t.eq(check({ groupId: 'g1', category: 'food' }).errors, {}); // kim has a g1 food budget; mine would be separate
    t.eq(check({ groupId: 'g1', category: 'groceries' }).errors, {});
    t.eq(check({ category: 'health' }).errors, {}); // the stored one is deleted
    t.eq(checkBudget(draft({ me: KIM, category: 'food' }), listed, []).errors, {}); // none of the listed personal budgets is kim's
  });
  t.check('checkBudget: a group budget needs a group I am in', () => {
    t.ok(check({ groupId: 'g9' }).errors.scope);
    t.eq(check({ groupId: 'g1' }).errors, {});
    t.ok(checkBudget(draft({ groupId: 'g1' }), listed, undefined).errors.scope);
  });
  t.check('checkBudget: every problem is reported at once', () => {
    const r = check({ category: 'food', limit: 'x' });
    t.eq(Object.keys(r.errors).sort(), ['category', 'limit']);
  });

  // ------------------------------------------------------------ words
  t.check('budget words: title, summary and the three statuses (and "At the limit", not "over by 0")', () => {
    const food = usageFor(60000, 50000);
    t.eq(budgetSummary(food), '₱500.00 of ₱600.00 · 83% used');
    t.eq(budgetStatus(food), 'Close to the limit');
    t.eq(budgetStatus(usageFor(200000, 115000)), 'On track');
    t.eq(budgetStatus(usageFor(40000, 50000)), 'Over the limit by ₱100.00');
    t.eq(budgetStatus(usageFor(15000, 15000)), 'At the limit');
    t.eq(budgetStatus(usageFor(15000, 15001)), 'Over the limit by ₱0.01');
    t.eq(budgetTitle(bud('x', null, null, 1)), 'Overall');
    t.eq(budgetTitle(bud('x', null, 'food', 1)), 'Food & Dining');
    t.eq(budgetTitle(bud('x', null, 'xyz', 1)), 'xyz');
    t.eq(budgetSummary(budgetUsage(bud('x', 'g1', 'food', 600000), PARITY_TXNS, MONTH, G1_MEMBERS)), '₱750.00 of ₱6,000.00 · 12% used');
  });

  // ------------------------------------------------------------ data.js: loading and writing
  const later = t.later || (() => {});
  const rowOf = (over = {}) => ({ id: 'b', owner_id: ME, group_id: null, category: null, monthly_limit: 100, updated_at: 5, deleted: false, ...over });
  const PERSONAL = { mode: 'personal', groupId: null };

  t.check('normalizeBudget: numbers become numbers, empty group and category become null, deleted is a real boolean', () => {
    t.eq(normalizeBudget({ id: 'a', owner_id: ME, group_id: '', category: '', monthly_limit: '250000', updated_at: '9', deleted: null }),
      { id: 'a', owner_id: ME, group_id: null, category: null, monthly_limit: 250000, updated_at: 9, deleted: false });
    t.eq(normalizeBudget(rowOf({ monthly_limit: null })).monthly_limit, 0);
    t.eq(normalizeBudget(rowOf({ deleted: true })).deleted, true);
  });
  later('loadBudgets: Personal asks for my live budgets with no group, and only those come back', async () => {
    const tables = { budgets: [
      rowOf({ id: 'mine' }), rowOf({ id: 'mine-food', category: 'food', monthly_limit: '5000' }),
      rowOf({ id: 'kim-personal', owner_id: KIM }), rowOf({ id: 'in-group', group_id: 'g1' }), rowOf({ id: 'gone', deleted: true }),
    ] };
    const client = fakeClient({ tables });
    const rows = await loadBudgets(client, { id: ME }, PERSONAL);
    t.eq(rows.map((r) => r.id), ['mine', 'mine-food']);
    t.eq(rows[1].monthly_limit, 5000);
    t.eq(client.calls[0].table, 'budgets');
    t.eq(client.calls[0].eqs, [['deleted', false], ['owner_id', ME], ['group_id', null]]);
  });
  later('loadBudgets: Group asks for the group\'s budgets, whoever owns them, and not other groups\' or personal ones', async () => {
    const tables = { budgets: [
      rowOf({ id: 'mine-g1', group_id: 'g1' }), rowOf({ id: 'kim-g1', group_id: 'g1', owner_id: KIM }), rowOf({ id: 'kim-g2', group_id: 'g2', owner_id: KIM }),
      rowOf({ id: 'kim-personal', owner_id: KIM }), rowOf({ id: 'g1-gone', group_id: 'g1', deleted: true }),
    ] };
    const client = fakeClient({ tables });
    const rows = await loadBudgets(client, { id: ME }, { mode: 'group', groupId: 'g1' });
    t.eq(rows.map((r) => r.id), ['kim-g1', 'mine-g1']);
    t.eq(client.calls[0].eqs, [['deleted', false], ['group_id', 'g1']]);
  });
  later('loadBudgets: more than one page all arrives; a failed page rejects instead of returning part of the list', async () => {
    const many = Array.from({ length: 2500 }, (_, i) => rowOf({ id: `b${String(i).padStart(5, '0')}` }));
    const rows = await loadBudgets(fakeClient({ tables: { budgets: many } }), { id: ME }, PERSONAL);
    t.eq(rows.length, 2500);
    let threw = '';
    try { await loadBudgets(fakeClient({ failOn: () => true }), { id: ME }, PERSONAL); } catch (e) { threw = e.message; }
    t.eq(threw, 'boom');
    t.eq(typeof fetchAllPages, 'function');
  });

  later('createBudget: inserts a new row owned by me with a fresh id, centavos and updated_at; returns the stored row', async () => {
    const client = fakeClient({ writeRows: (q) => [{ ...q.payload }] });
    const res = await createBudget(client, ME, { groupId: null, category: 'food', monthlyLimit: 60000 }, 1234);
    t.ok(res.ok);
    const sent = client.calls[0];
    t.eq([sent.table, sent.op], ['budgets', 'insert']);
    t.eq(Object.keys(sent.payload).sort(), ['category', 'deleted', 'group_id', 'id', 'monthly_limit', 'owner_id', 'updated_at']);
    t.ok(/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/.test(sent.payload.id), `id ${sent.payload.id} is not a v4 uuid`);
    t.eq({ ...sent.payload, id: 'x' }, { id: 'x', owner_id: ME, group_id: null, category: 'food', monthly_limit: 60000, updated_at: 1234, deleted: false });
    t.eq(res.row.monthly_limit, 60000);
    const other = await createBudget(client, ME, { groupId: 'g1', category: null, monthlyLimit: 1 }, 1);
    t.ok(other.row.id !== res.row.id, 'every budget gets its own id');
    t.eq([client.calls[1].payload.group_id, client.calls[1].payload.category], ['g1', null]);
  });
  later('createBudget: an id can be given; zero rows back is a refusal; a server error is reported', async () => {
    const client = fakeClient({ writeRows: (q) => [q.payload] });
    t.eq((await createBudget(client, ME, { groupId: null, category: null, monthlyLimit: 5 }, 1, 'fixed-id')).row.id, 'fixed-id');
    const refused = await createBudget(fakeClient({ writeRows: () => [] }), ME, { groupId: 'g1', category: null, monthlyLimit: 5 }, 1);
    t.eq([refused.ok, refused.reason], [false, 'refused']);
    const err = await createBudget(fakeClient({ failOn: () => true }), ME, { groupId: null, category: null, monthlyLimit: 5 }, 1);
    t.eq([err.ok, err.reason, err.message], [false, 'error', 'boom']);
  });
  later('updateOwnBudget: scoped by id and owner; success returns the stored row with the new limit', async () => {
    const client = fakeClient({ writeRows: (q) => [{ ...rowOf({ id: 'b1' }), ...q.payload }] });
    const res = await updateOwnBudget(client, 'b1', ME, { category: 'food', monthly_limit: 70000 }, 777);
    t.ok(res.ok);
    t.eq(client.calls[0].payload, { category: 'food', monthly_limit: 70000, updated_at: 777 });
    t.eq(client.calls[0].eqs, [['id', 'b1'], ['owner_id', ME]]);
    t.eq([client.calls[0].table, client.calls[0].op], ['budgets', 'update']);
    t.eq([res.row.monthly_limit, res.row.category, res.row.updated_at], [70000, 'food', 777]);
  });
  later('updateOwnBudget: zero rows back is a refusal; a newer stored row (LWW) is discarded; errors are reported', async () => {
    const refused = await updateOwnBudget(fakeClient({ writeRows: () => [] }), 'b1', 'someone', { monthly_limit: 1 }, 5);
    t.eq([refused.ok, refused.reason], [false, 'refused']);
    t.ok(refused.message.includes('budget'));
    const lww = await updateOwnBudget(fakeClient({ writeRows: () => [rowOf({ id: 'b1', updated_at: 9999, monthly_limit: 42 })] }), 'b1', ME, { monthly_limit: 1 }, 5);
    t.eq([lww.ok, lww.reason, lww.row.updated_at, lww.row.monthly_limit], [false, 'discarded', 9999, 42]);
    const err = await updateOwnBudget(fakeClient({ failOn: () => true }), 'b1', ME, {}, 5);
    t.eq([err.ok, err.reason, err.message], [false, 'error', 'boom']);
  });
  later('tombstoneBudget: sets deleted = true with the new updated_at and never deletes the row', async () => {
    const client = fakeClient({ writeRows: (q) => [{ ...rowOf({ id: 'b1' }), ...q.payload }] });
    const res = await tombstoneBudget(client, 'b1', ME, 4242);
    t.eq(client.calls[0].payload, { deleted: true, updated_at: 4242 });
    t.eq(client.calls[0].op, 'update');
    t.ok(res.ok);
    t.eq(res.row.deleted, true);
    t.eq((await tombstoneBudget(fakeClient({ writeRows: () => [] }), 'b1', 'someone', 1)).reason, 'refused');
    const lww = await tombstoneBudget(fakeClient({ writeRows: () => [rowOf({ id: 'b1', updated_at: 99 })] }), 'b1', ME, 1);
    t.eq([lww.reason, lww.row.deleted], ['discarded', false]);
  });
  later('updateOwnTxn keeps its wording after the shared update helper', async () => {
    const refused = await updateOwnTxn(fakeClient({ writeRows: () => [] }), 'a', 'x', {}, 5);
    t.eq(refused.message, 'The server did not accept the change. This row may not be yours, or it may have been removed.');
    const lww = await updateOwnTxn(fakeClient({ writeRows: () => [{ id: 'a', updated_at: 9 }] }), 'a', ME, {}, 5);
    t.ok(lww.message.startsWith('A newer change to this row already exists'));
  });

  // ------------------------------------------------------------ the Budgets card (needs a DOM)
  if (typeof document === 'undefined') return;

  const TODAY = '2026-11-24';
  const HOSTILE = '<img src=x onerror="window.__pwned=1">';
  const q = (root, sel) => root.querySelector(sel);
  const qa = (root, sel) => [...root.querySelectorAll(sel)];
  const texts = (root, sel) => qa(root, sel).map((n) => n.textContent);
  const click = (node) => node.dispatchEvent(new window.MouseEvent('click', { bubbles: true }));
  const submit = (form) => form.dispatchEvent(new window.Event('submit', { bubbles: true, cancelable: true }));
  const type = (input, value) => { input.value = value; input.dispatchEvent(new window.Event('input', { bubbles: true })); };
  const pick = (select, value) => { select.value = value; select.dispatchEvent(new window.Event('change', { bubbles: true })); };
  const flush = () => new Promise((resolve) => setTimeout(resolve, 0));
  const fresh = () => {
    resetDashboardPeriod();
    const root = document.createElement('div');
    document.body.append(root);
    return root;
  };
  const card = (root) => qa(root, '.dash-card').find((c) => q(c, 'h3').textContent === 'Budgets vs actual');
  const rows = (root) => qa(card(root), '.dash-budget');
  const focusKey = () => document.activeElement && document.activeElement.getAttribute('data-dash-focus');

  const allBudgets = () => [
    ...PARITY.map((p) => ({ ...p[0] })),
    bud('kim-personal', null, 'travel', 12345, KIM), bud('g2-all', 'g2', null, 99999),
    bud('b7', 'g1', 'groceries', 20000, KIM),
  ];
  const okWrite = async () => ({ ok: true, budgets: [] });
  const personalCtx = (over = {}) => ({
    mode: 'personal', meId: ME, groupName: null, today: TODAY, groupId: null, myGroupIds: ['g1'],
    members: [{ id: ME, name: 'Matt' }], wallets: [], txns: PARITY_TXNS, budgets: allBudgets(),
    onSaveBudget: okWrite, onDeleteBudget: okWrite, ...over,
  });
  const groupCtx = (over = {}) => ({
    mode: 'group', meId: ME, groupName: 'Family', today: TODAY, groupId: 'g1', myGroupIds: ['g1'],
    members: [{ id: ME, name: 'Matt' }, { id: KIM, name: 'Kim' }], wallets: [], txns: PARITY_TXNS, budgets: allBudgets(),
    onSaveBudget: okWrite, onDeleteBudget: okWrite, ...over,
  });

  t.check('budgets card: Personal lists my budgets in order with numbers, words and scope; others\' budgets are hidden', () => {
    const root = fresh();
    renderDashboard(root, personalCtx());
    t.eq(texts(card(root), '.dash-budget-name'), ['Overall', 'Food & Dining', 'Groceries', 'Other']);
    t.eq(texts(card(root), '.dash-budget-text'), [
      '₱1,150.00 of ₱2,000.00 · 57% used', '₱500.00 of ₱600.00 · 83% used', '₱500.00 of ₱400.00 · 125% used', '₱150.00 of ₱150.00 · 100% used',
    ]);
    t.eq(texts(card(root), '.dash-budget-status'), ['On track', 'Close to the limit', 'Over the limit by ₱100.00', 'At the limit']);
    t.eq([...new Set(texts(card(root), '.dash-budget-scope'))], ['Personal']);
    t.ok(!card(root).textContent.includes('Travel'), 'kim\'s personal budget leaked');
    t.ok(!card(root).textContent.includes('999.99'), 'another group\'s budget leaked');
    t.ok(card(root).textContent.includes('November 2026'));
    resetDashboardPeriod();
  });
  t.check('budgets card: the bar stops at the track end for an over budget, fills to the true share otherwise, and is hidden from screen readers', () => {
    const root = fresh();
    renderDashboard(root, personalCtx());
    const bars = rows(root).map((li) => q(li, '.dash-bar'));
    t.ok(bars[0].getAttribute('d').includes('H341Q345'), `57.5% of 600: ${bars[0].getAttribute('d')}`);
    t.ok(bars[2].getAttribute('d').includes('H596Q600'), `125% must stop at 600: ${bars[2].getAttribute('d')}`);
    t.eq(bars.map((b) => b.getAttribute('class')), ['dash-bar dash-lvl-ok', 'dash-bar dash-lvl-warn', 'dash-bar dash-lvl-over', 'dash-bar dash-lvl-over']);
    for (const svg of qa(card(root), 'svg.dash-progress')) t.eq(svg.getAttribute('aria-hidden'), 'true');
    resetDashboardPeriod();
  });
  t.check('budgets card: Group lists the group\'s budgets with every member\'s spending; others\' rows are read-only and say who set them', () => {
    const root = fresh();
    renderDashboard(root, groupCtx());
    t.eq(texts(card(root), '.dash-budget-name'), ['Overall', 'Food & Dining', 'Groceries']);
    t.eq(texts(card(root), '.dash-budget-text'), [
      '₱1,550.00 of ₱1,500.00 · 103% used', '₱750.00 of ₱1,000.00 · 75% used', '₱650.00 of ₱200.00 · 325% used',
    ]);
    t.eq(texts(card(root), '.dash-budget-scope'), ['Group: Family', 'Group: Family', 'Group: Family']);
    t.eq(texts(card(root), '.dash-budget-by'), ['Set by you', 'Set by you', 'Set by Kim']);
    const [overall, food, groceries] = rows(root);
    t.eq(qa(overall, 'button').map((b) => b.textContent), ['Edit', 'Delete']);
    t.eq(qa(food, 'button').map((b) => b.textContent), ['Edit', 'Delete']);
    t.eq(qa(groceries, 'button').length, 0);
    t.ok(!card(root).textContent.includes('₱123.45'), 'a personal budget leaked into the group view');
    resetDashboardPeriod();
  });
  t.check('budgets card: buttons have accessible names and the form fields have labels', () => {
    const root = fresh();
    renderDashboard(root, personalCtx());
    t.eq(qa(card(root), '.dash-budget button').map((b) => b.getAttribute('aria-label')).slice(0, 2), ['Edit Overall budget', 'Delete Overall budget']);
    click(q(root, '[data-dash-focus="budget-add"]'));
    t.eq(texts(root, 'label[for="dash-b-category"], label[for="dash-b-limit"]'), ['Category', 'Monthly limit (pesos)']);
    t.eq(document.activeElement && document.activeElement.id, 'dash-b-category');
    resetDashboardPeriod();
  });
  t.check('budgets card: year view shows the current month with a note; an earlier year shows December', () => {
    const root = fresh();
    renderDashboard(root, personalCtx({ txns: [...PARITY_TXNS, tx('old', ME, 'food', '2025-06-01', 100)] })); // 2025 must be a choice
    click(q(root, '[data-dash-focus="kind-year"]'));
    t.ok(texts(card(root), '.dash-note').some((n) => n.includes('current month') && n.includes('November 2026')), texts(card(root), '.dash-note').join(' | '));
    t.eq(texts(card(root), '.dash-budget-text')[0], '₱1,150.00 of ₱2,000.00 · 57% used');
    pick(q(root, '.dash-select'), '2025');
    t.ok(texts(card(root), '.dash-note').some((n) => n.includes('December 2025')));
    t.eq(texts(card(root), '.dash-budget-text')[0], '₱0.00 of ₱2,000.00 · 0% used');
    resetDashboardPeriod();
  });
  t.check('budgets card: a month with nothing logged still shows the budgets (at 0%)', () => {
    const root = fresh();
    renderDashboard(root, personalCtx({ today: '2027-03-09' }));
    t.ok(q(root, '.dash-empty'), 'the empty state shows');
    t.eq(texts(card(root), '.dash-budget-text')[0], '₱0.00 of ₱2,000.00 · 0% used');
    resetDashboardPeriod();
  });
  t.check('budgets card: with no budgets it says so and offers Add; without callbacks it is read-only', () => {
    const root = fresh();
    renderDashboard(root, personalCtx({ budgets: [] }));
    t.ok(card(root).textContent.includes('no personal budgets'));
    t.ok(q(root, '[data-dash-focus="budget-add"]'));
    renderDashboard(root, groupCtx({ budgets: [] }));
    t.ok(card(root).textContent.includes('no budgets yet'));
    renderDashboard(root, personalCtx({ onSaveBudget: undefined, onDeleteBudget: undefined }));
    t.eq(qa(card(root), 'button').length, 0);
    t.eq(rows(root).length, 4);
    resetDashboardPeriod();
  });
  t.check('budgets card: a failed load is an error with Try again, never "no budgets"', () => {
    const root = fresh();
    let retried = 0;
    renderDashboard(root, personalCtx({ budgets: [], budgetsError: 'net down', onRetryBudgets: () => { retried += 1; } }));
    t.ok(q(card(root), '[role="alert"]').textContent.includes('net down'));
    t.ok(!card(root).textContent.includes('no personal budgets'));
    t.eq(qa(card(root), '[data-dash-focus="budget-add"]').length, 0);
    click(qa(card(root), 'button').find((b) => b.textContent === 'Try again'));
    t.eq(retried, 1);
    resetDashboardPeriod();
  });
  t.check('budgets card: hostile names stay text; nothing is injected or run', () => {
    const root = fresh();
    renderDashboard(root, groupCtx({ groupName: HOSTILE, members: [{ id: ME, name: 'Matt' }, { id: KIM, name: HOSTILE }] }));
    t.eq(root.querySelectorAll('img').length, 0);
    t.ok(card(root).textContent.includes(HOSTILE));
    t.ok(texts(card(root), '.dash-budget-by').includes(`Set by ${HOSTILE}`));
    t.ok(!globalThis.window || !window.__pwned, 'a handler ran');
    resetDashboardPeriod();
  });
  t.check('budgets card: showing another scope drops an open form; a data refresh keeps what was typed', () => {
    const root = fresh();
    const ctx = personalCtx();
    renderDashboard(root, ctx);
    click(q(root, '[data-dash-focus="budget-add"]'));
    pick(q(root, '#dash-b-category'), 'travel');
    type(q(root, '#dash-b-limit'), '7,50');
    renderDashboard(root, ctx);
    t.eq([q(root, '#dash-b-category').value, q(root, '#dash-b-limit').value], ['travel', '7,50']);
    renderDashboard(root, groupCtx());
    t.eq(qa(root, '.dash-bform').length, 0);
    resetDashboardPeriod();
  });

  // The async scenarios share the dashboard's remembered state, so they run one after another.
  let chain = Promise.resolve();
  const scenario = (name, fn) => later(name, () => { chain = chain.then(fn, fn); return chain; });
  const openAdd = (root) => click(q(root, '[data-dash-focus="budget-add"]'));
  const fill = (root, category, limit) => {
    pick(q(root, '#dash-b-category'), category);
    type(q(root, '#dash-b-limit'), limit);
  };

  scenario('budgets card: a bad limit shows an inline message, keeps the input and does not call the server', async () => {
    const root = fresh();
    let calls = 0;
    renderDashboard(root, personalCtx({ onSaveBudget: async () => { calls += 1; return { ok: true }; } }));
    openAdd(root);
    fill(root, 'travel', '12.345');
    submit(q(root, '.dash-bform'));
    await flush();
    t.ok(q(root, '#dash-b-limit-err').textContent.includes('Enter a limit'));
    t.eq(q(root, '#dash-b-limit').getAttribute('aria-invalid'), 'true');
    t.eq([q(root, '#dash-b-category').value, q(root, '#dash-b-limit').value], ['travel', '12.345']);
    t.ok(q(root, '.dash-bform [role="alert"]').textContent.includes('Nothing was saved'));
    t.eq(document.activeElement.id, 'dash-b-limit');
    type(q(root, '#dash-b-limit'), '0');
    submit(q(root, '.dash-bform'));
    await flush();
    t.ok(q(root, '#dash-b-limit-err').textContent.includes('more than'));
    t.eq(calls, 0);
    resetDashboardPeriod();
  });
  scenario('budgets card: a duplicate category is refused inline', async () => {
    const root = fresh();
    let calls = 0;
    renderDashboard(root, personalCtx({ onSaveBudget: async () => { calls += 1; return { ok: true }; } }));
    openAdd(root);
    fill(root, 'groceries', '500');
    submit(q(root, '.dash-bform'));
    await flush();
    t.ok(q(root, '#dash-b-category-err').textContent.includes('already have a Groceries budget'));
    t.eq(document.activeElement.id, 'dash-b-category');
    t.eq(calls, 0);
    resetDashboardPeriod();
  });
  scenario('budgets card: a valid new budget is sent as centavos for the scope, then the list, status and focus update', async () => {
    const root = fresh();
    const sent = [];
    renderDashboard(root, personalCtx({
      onSaveBudget: async (change) => {
        sent.push(change);
        return { ok: true, budgets: [...allBudgets(), bud('new', null, 'travel', change.monthlyLimit)] };
      },
    }));
    openAdd(root);
    fill(root, 'travel', '2,500');
    submit(q(root, '.dash-bform'));
    await flush();
    t.eq(sent, [{ id: null, groupId: null, category: 'travel', monthlyLimit: 250000 }]);
    t.eq(qa(root, '.dash-bform').length, 0);
    t.eq(texts(card(root), '.dash-budget-name'), ['Overall', 'Food & Dining', 'Groceries', 'Other', 'Travel']);
    t.eq(texts(card(root), '.dash-budget-text')[4], '₱0.00 of ₱2,500.00 · 0% used');
    t.ok(card(root).textContent.includes('Saved the Travel budget.'));
    t.eq(focusKey(), 'budget-add');
    resetDashboardPeriod();
  });
  scenario('budgets card: in Group view a new budget is for the selected group', async () => {
    const root = fresh();
    const sent = [];
    renderDashboard(root, groupCtx({ onSaveBudget: async (c) => { sent.push(c); return { ok: true }; } }));
    openAdd(root);
    t.ok(q(root, '.dash-bform').textContent.includes('Every member sees it'));
    fill(root, 'travel', '1000');
    submit(q(root, '.dash-bform'));
    await flush();
    t.eq(sent, [{ id: null, groupId: 'g1', category: 'travel', monthlyLimit: 100000 }]);
    resetDashboardPeriod();
  });
  scenario('budgets card: a refused, failed or throwing save shows the reason and keeps what was typed', async () => {
    const root = fresh();
    renderDashboard(root, personalCtx({ onSaveBudget: async () => ({ ok: false, message: 'The server did not accept the new budget.' }) }));
    openAdd(root);
    fill(root, 'travel', '2,500');
    submit(q(root, '.dash-bform'));
    await flush();
    const alert = q(root, '.dash-bform [role="alert"]').textContent;
    t.ok(alert.includes('did not accept') && alert.includes('Your input is still here'), alert);
    t.eq([q(root, '#dash-b-category').value, q(root, '#dash-b-limit').value], ['travel', '2,500']);
    const root2 = fresh();
    renderDashboard(root2, personalCtx({ onSaveBudget: async () => { throw new Error('offline'); } }));
    openAdd(root2);
    fill(root2, 'travel', '10');
    submit(q(root2, '.dash-bform'));
    await flush();
    t.ok(q(root2, '.dash-bform [role="alert"]').textContent.includes('offline'));
    t.eq(q(root2, '#dash-b-limit').value, '10');
    resetDashboardPeriod();
  });
  scenario('budgets card: Edit opens the form with the current values; saving sends the id and the new centavos', async () => {
    const root = fresh();
    const sent = [];
    renderDashboard(root, personalCtx({ onSaveBudget: async (c) => { sent.push(c); return { ok: true }; } }));
    click(q(rows(root)[1], '[data-dash-focus="budget-edit-b2"]'));
    t.eq([q(root, '#dash-b-category').value, q(root, '#dash-b-limit').value], ['food', '600.00']);
    t.eq(q(root, '.dash-bform-title').textContent, 'Edit budget');
    t.eq(document.activeElement.id, 'dash-b-category');
    type(q(root, '#dash-b-limit'), '650');
    submit(q(root, '.dash-bform'));
    await flush();
    t.eq(sent, [{ id: 'b2', groupId: null, category: 'food', monthlyLimit: 65000 }]);
    t.eq(qa(root, '.dash-bform').length, 0);
    resetDashboardPeriod();
  });
  scenario('budgets card: Cancel and Escape close the form without saving and give focus back', async () => {
    const root = fresh();
    let calls = 0;
    renderDashboard(root, personalCtx({ onSaveBudget: async () => { calls += 1; return { ok: true }; } }));
    click(q(root, '[data-dash-focus="budget-edit-b2"]'));
    click(qa(root, '.dash-bform button').find((b) => b.textContent === 'Cancel'));
    t.eq(qa(root, '.dash-bform').length, 0);
    t.eq(focusKey(), 'budget-edit-b2');
    openAdd(root);
    q(root, '.dash-bform').dispatchEvent(new window.KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));
    t.eq(qa(root, '.dash-bform').length, 0);
    t.eq(focusKey(), 'budget-add');
    t.eq(calls, 0);
    resetDashboardPeriod();
  });
  scenario('budgets card: Delete asks first; Cancel keeps it; Yes sends the id and the budget leaves the list', async () => {
    const root = fresh();
    const deleted = [];
    renderDashboard(root, personalCtx({
      onDeleteBudget: async (id) => { deleted.push(id); return { ok: true, budgets: allBudgets().filter((b) => b.id !== id) }; },
    }));
    click(q(root, '[data-dash-focus="budget-delete-b2"]'));
    t.ok(q(root, '.dash-confirm').textContent.includes('Delete the Food & Dining budget?'));
    t.eq(focusKey(), 'budget-no-b2');
    t.eq(deleted, []);
    click(q(root, '[data-dash-focus="budget-no-b2"]'));
    t.eq(qa(root, '.dash-confirm').length, 0);
    t.eq(focusKey(), 'budget-delete-b2');
    click(q(root, '[data-dash-focus="budget-delete-b2"]'));
    click(q(root, '[data-dash-focus="budget-yes-b2"]'));
    await flush();
    t.eq(deleted, ['b2']);
    t.eq(texts(card(root), '.dash-budget-name'), ['Overall', 'Groceries', 'Other']);
    t.ok(card(root).textContent.includes('Deleted the Food & Dining budget.'));
    resetDashboardPeriod();
  });
  scenario('budgets card: a failed delete says so and keeps the budget and the question', async () => {
    const root = fresh();
    renderDashboard(root, personalCtx({ onDeleteBudget: async () => ({ ok: false, message: 'The server did not accept the change.' }) }));
    click(q(root, '[data-dash-focus="budget-delete-b2"]'));
    click(q(root, '[data-dash-focus="budget-yes-b2"]'));
    await flush();
    t.ok(q(root, '.dash-confirm [role="alert"]').textContent.includes('The budget was not deleted.'));
    t.eq(rows(root).length, 4);
    t.eq(focusKey(), 'budget-no-b2');
    resetDashboardPeriod();
  });
  scenario('budgets card: a discarded save (newer stored row) shows the stored budget and keeps what was typed', async () => {
    const root = fresh();
    const stored = allBudgets().map((b) => (b.id === 'b2' ? { ...b, monthly_limit: 99900, updated_at: 9999 } : b));
    renderDashboard(root, personalCtx({ onSaveBudget: async () => ({ ok: false, message: 'A newer change to this budget already exists.', budgets: stored }) }));
    click(q(root, '[data-dash-focus="budget-edit-b2"]'));
    type(q(root, '#dash-b-limit'), '650');
    submit(q(root, '.dash-bform'));
    await flush();
    t.eq(texts(card(root), '.dash-budget-text')[1], '₱500.00 of ₱999.00 · 50% used'); // the list shows the stored limit
    t.eq(q(root, '#dash-b-limit').value, '650'); // the typed value is still in the form
    t.ok(q(root, '.dash-bform [role="alert"]').textContent.includes('newer change'));
    resetDashboardPeriod();
  });
}
