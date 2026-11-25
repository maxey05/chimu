// Checks for the goal math in stats.js, the wording in goals.js, the goal calls in data.js and the Savings goals card of
// the dashboard (RFC-011). Runs in the browser (test.html) and in Node (node web/test-node.mjs); the card checks need a DOM,
// so they only run when a `document` exists (the browser page, or jsdom), like test-budgets.js.
import {
  isDateText, goalPercent, goalLeft, goalReached, monthsLeft, neededPerMonth, goalPlan, goalProgress, goalsProgress, visibleGoals,
} from './stats.js';
import { dateLabel, goalSummary, goalStatus, goalPlanText, goalDeadlineText } from './goals.js';
import { fetchAllPages, loadGoals, normalizeGoal } from './data.js';
import { progressBar } from './charts.js';
import { renderDashboard, resetDashboardPeriod } from './dashboard.js';

// --- the parity vector: keep in sync with GoalsTest.kt (a Node-only check below compares the two files) ---------------------
// today = '2026-11-25'. Owners: me, kim. Amounts are centavos. See the comment block in GoalsTest.kt for the story of each row.
const TODAY = '2026-11-25';
const ME = 'me';
const KIM = 'kim';
// [id, owner, type, opening_balance, archived]
const WALLETS = [
  ['wA', 'me', 'savings', 0, false], ['wB', 'me', 'savings', 100000, false], ['wC', 'me', 'savings', 0, false],
  ['wD', 'me', 'cash', 0, false], ['wE', 'kim', 'savings', 0, false], ['wF', 'kim', 'cash', 0, false],
  ['wZ', 'me', 'savings', 777, true],
];
// [id, owner, type, wallet, to_wallet, amount, date, deleted]
const TXNS = [
  ['t1', 'me', 'transfer', 'wD', 'wA', 200000, '2026-11-01', false],
  ['t2', 'me', 'transfer', 'wD', 'wA', 150000, '2026-11-10', false],
  ['t3', 'me', 'transfer', 'wA', 'wD', 50000, '2026-11-12', false],
  ['t4', 'me', 'expense', 'wA', null, 20000, '2026-11-13', false],
  ['t5', 'me', 'income', 'wA', null, 10000, '2026-11-14', false],
  ['t6', 'me', 'transfer', 'wD', 'wA', 99900, '2026-11-15', true],
  ['t7', 'me', 'transfer', 'wD', 'wB', 400000, '2026-11-15', false],
  ['t8', 'me', 'transfer', 'wD', 'wC', 5000, '2026-11-16', false],
  ['t9', 'kim', 'transfer', 'wF', 'wE', 300000, '2026-11-17', false],
];
// [id, owner, name, target, deadline, wallet]
const GOALS = [
  ['g1', 'me', 'Baguio trip', 1200000, '2027-02-25', 'wA'],
  ['g2', 'me', 'Laptop', 500000, '2027-01-10', 'wB'],
  ['g3', 'me', 'Emergency fund', 800000, null, 'wC'],
  ['g4', 'me', 'Tuition', 600000, '2026-11-30', 'wA'],
  ['g5', 'me', 'Old debt', 1000000, '2026-10-31', 'wC'],
  ['g6', 'me', 'Phone', 100000, '2027-03-01', 'wB'],
  ['g7', 'me', 'Gift', 700000, '2026-11-25', 'wC'],
  ['g8', 'me', 'No target', 0, null, 'wA'],
  ['g9', 'me', 'Lost wallet goal', 1000000, '2027-02-25', 'wZ'],
  ['g10', 'me', 'Camera', 1000000, '2027-02-24', 'wC'],
  ['g11', 'kim', 'Kim\'s bike', 1000000, '2027-05-25', 'wE'],
  ['g12', 'me', 'House deposit', 1200000, '2027-11-25', 'wC'],
  ['g13', 'me', 'Concert', 300000, '2026-12-01', 'wC'],
];
// [id, saved, left, percent, reached, plan code]
const EXPECT = [
  ['g1', 290000, 910000, 24, false, 'MONTHLY:303334:3'],
  ['g2', 500000, 0, 100, true, 'REACHED'],
  ['g3', 5000, 795000, 0, false, 'NODEADLINE'],
  ['g4', 290000, 310000, 48, false, 'MONTHLY:310000:1'],
  ['g5', 5000, 995000, 0, false, 'OVERDUE'],
  ['g6', 500000, 0, 500, true, 'REACHED'],
  ['g7', 5000, 695000, 0, false, 'MONTHLY:695000:1'],
  ['g8', 290000, 0, 0, false, 'NOTARGET'],
  ['g9', 0, 1000000, 0, false, 'WALLET'],
  ['g10', 5000, 995000, 0, false, 'MONTHLY:497500:2'],
  ['g11', 300000, 700000, 30, false, 'MONTHLY:116667:6'],
  ['g12', 5000, 1195000, 0, false, 'MONTHLY:99584:12'],
  ['g13', 5000, 295000, 1, false, 'MONTHLY:295000:1'],
];
// The phone's list for me, in order, and the group view's (me + kim).
const PHONE_ORDER = ['g5', 'g7', 'g4', 'g13', 'g2', 'g10', 'g1', 'g9', 'g6', 'g12', 'g3', 'g8'];
const GROUP_ORDER = ['g5', 'g7', 'g4', 'g13', 'g2', 'g10', 'g1', 'g9', 'g6', 'g11', 'g12', 'g3', 'g8'];

const wallets = WALLETS.map(([id, owner, type, opening, archived]) => ({
  id, owner_id: owner, name: `w-${id}`, type, opening_balance: opening, deleted: archived,
}));
const txns = TXNS.map(([id, owner, type, wallet, to, amount, date, deleted]) => ({
  id, owner_id: owner, type, wallet_id: wallet, to_wallet_id: to, amount, date, deleted,
}));
const goals = GOALS.map(([id, owner, name, target, deadline, wallet]) => ({
  id, owner_id: owner, name, target, deadline, wallet_id: wallet, updated_at: 1, deleted: false,
}));
const goalById = (id) => goals.find((g) => g.id === id);

const planCode = (plan) => (plan.kind === 'monthly' ? `MONTHLY:${plan.perMonth}:${plan.months}` : plan.kind.toUpperCase());
const progressAll = (extraTxns = []) => goalsProgress(goals, wallets, [...txns, ...extraTxns], TODAY);
const progressOf = (id, extraTxns) => progressAll(extraTxns).find((p) => p.goal.id === id);

// A tiny stand-in for the supabase-js query builder: select with eq, in, order, range.
function fakeClient(rows, { failOn = null } = {}) {
  const calls = [];
  return {
    calls,
    from(table) {
      const q = { table, eqs: [], ins: [], order: [], range: null };
      const builder = {
        select() { return builder; },
        eq(col, val) { q.eqs.push([col, val]); return builder; },
        in(col, vals) { q.ins.push([col, vals]); return builder; },
        order(col) { q.order.push(col); return builder; },
        range(a, b) { q.range = [a, b]; return builder; },
        then(resolve, reject) {
          calls.push(q);
          let result;
          if (failOn && failOn(q)) result = { data: null, error: { message: 'boom' } };
          else {
            let out = rows.filter((r) => q.eqs.every(([c, v]) => r[c] === v) && q.ins.every(([c, vs]) => vs.includes(r[c])));
            for (const col of [...q.order].reverse()) out = [...out].sort((a, b) => (a[col] < b[col] ? -1 : a[col] > b[col] ? 1 : 0));
            if (q.range) out = out.slice(q.range[0], q.range[1] + 1);
            result = { data: out, error: null };
          }
          return Promise.resolve(result).then(resolve, reject);
        },
      };
      return builder;
    },
  };
}

export function tests(t) {
  // ------------------------------------------------------------ the parity vector
  t.check('goals: parity vector g1..g13 gives the same saved, left, percent, reached and plan as the phone', () => {
    const all = progressAll();
    t.eq(all.map((p) => p.goal.id), GOALS.map((g) => g[0]));
    for (const [id, saved, left, percent, reached, plan] of EXPECT) {
      const p = all.find((x) => x.goal.id === id);
      t.eq([id, p.saved, p.left, p.percent, p.reached, planCode(p.plan)], [id, saved, left, percent, reached, plan]);
    }
  });
  t.check('goals: the list order is soonest deadline first, none last, then name; Personal never lists kim, Group does', () => {
    t.eq(visibleGoals(goals, new Set([ME])).map((g) => g.id), PHONE_ORDER);
    t.eq(visibleGoals(goals, new Set([ME, KIM])).map((g) => g.id), GROUP_ORDER);
    t.eq(visibleGoals(goals, new Set([KIM])).map((g) => g.id), ['g11']);
    t.eq(visibleGoals(goals, new Set(['nobody'])), []);
  });
  t.check('goals: the words match the phone for every goal (the archived-wallet plan line differs on purpose: the web cannot edit)', () => {
    const words = (id) => { const p = progressOf(id); return [goalSummary(p), goalStatus(p), goalPlanText(p), goalDeadlineText(p.goal)]; };
    t.eq(words('g1'), ['₱2,900.00 of ₱12,000.00 · 24% saved', '₱9,100.00 left', 'Needed per month: ₱3,033.34 (3 months left)', 'Deadline: Feb 25, 2027']);
    t.eq(words('g2'), ['₱5,000.00 of ₱5,000.00 · 100% saved', 'Goal reached', null, 'Deadline: Jan 10, 2027']);
    t.eq(words('g3'), ['₱50.00 of ₱8,000.00 · 0% saved', '₱7,950.00 left', 'No deadline', null]);
    t.eq(words('g4'), ['₱2,900.00 of ₱6,000.00 · 48% saved', '₱3,100.00 left', 'Needed per month: ₱3,100.00 (1 month left)', 'Deadline: Nov 30, 2026']);
    t.eq(words('g5'), ['₱50.00 of ₱10,000.00 · 0% saved', '₱9,950.00 left', 'Deadline passed (Oct 31, 2026)', 'Deadline: Oct 31, 2026']);
    t.eq(words('g6'), ['₱5,000.00 of ₱1,000.00 · 500% saved', 'Goal reached', null, 'Deadline: Mar 1, 2027']);
    t.eq(words('g7'), ['₱50.00 of ₱7,000.00 · 0% saved', '₱6,950.00 left', 'Needed per month: ₱6,950.00 (1 month left)', 'Deadline: Nov 25, 2026']);
    t.eq(words('g8'), ['₱2,900.00 saved · no target set', 'No target set', null, null]);
    t.eq(words('g9').filter((_, i) => i !== 2), ['Target ₱10,000.00', 'Savings wallet unavailable', 'Deadline: Feb 25, 2027']);
    t.eq(goalPlanText(progressOf('g9')), 'Edit this goal on your phone to choose another savings wallet.');
    t.eq(goalPlanText(progressOf('g10')), 'Needed per month: ₱4,975.00 (2 months left)');
  });

  // ------------------------------------------------------------ the acceptance cases
  t.check('goals: needed per month for a goal 3 months out is the left amount over 3, rounded up', () => {
    const p = progressOf('g1');
    t.eq(p.plan, { kind: 'monthly', perMonth: 303334, months: 3 });
    t.ok(303334 * 3 >= p.left && 303333 * 3 < p.left);
    t.eq(progressOf('g10').plan, { kind: 'monthly', perMonth: 497500, months: 2 }); // one day earlier: the last month is not whole
  });
  t.check('goals: a transfer into the savings wallet moves the progress (percent, left, bar fraction)', () => {
    const before = progressOf('g1');
    const into = { id: 'new', owner_id: ME, type: 'transfer', wallet_id: 'wD', to_wallet_id: 'wA', amount: 150000, date: TODAY, deleted: false };
    const after = progressOf('g1', [into]);
    t.eq([before.percent, after.percent], [24, 36]);
    t.eq([after.saved - before.saved, before.left - after.left], [150000, 150000]);
    t.ok(after.barFraction > before.barFraction);
    t.eq(progressOf('g3', [into]).saved, progressOf('g3').saved); // another wallet is untouched
    const out = { ...into, id: 'out', wallet_id: 'wA', to_wallet_id: 'wD' }; // and money moved out lowers it again
    t.eq(progressOf('g1', [into, out]).saved, before.saved);
  });

  // ------------------------------------------------------------ percent, left, reached
  t.check('goals: percent is floored, zero without savings or a target, and may pass 100 while the bar stops at full', () => {
    t.eq([goalPercent(799, 1000), goalPercent(9999, 10000), goalPercent(10000, 10000), goalPercent(1, 1000)], [79, 99, 100, 0]);
    t.eq([goalPercent(0, 1000), goalPercent(-500, 1000), goalPercent(500, 0), goalPercent(500, -1)], [0, 0, 0, 0]);
    t.eq(progressOf('g6').percent, 500);
    t.eq([progressOf('g6').barFraction, progressOf('g8').barFraction, progressOf('g9').barFraction], [1, 0, 0]);
    t.eq(goalProgress(goalById('g1'), 600000, TODAY).barFraction, 0.5);
  });
  t.check('goals: percent stays exact for the biggest amounts the app accepts (BigInt, no float rounding)', () => {
    t.eq(goalPercent(99999999999999, 100000000000000), 99);
    t.eq(goalPercent(100000000000000, 100000000000000), 100);
    t.eq(goalPercent(9007199254740991, 9007199254740992), 99);
  });
  t.check('goals: left is never negative and reached needs a target', () => {
    t.eq([goalLeft(2000, 1000), goalLeft(0, 1000), goalLeft(-500, 1000), goalLeft(500, 0)], [0, 1000, 1500, 0]);
    t.eq([goalReached(1000, 1000), goalReached(999, 1000), goalReached(0, 0), goalReached(10, -5)], [true, false, false, false]);
  });
  t.check('goals: a wallet below zero gives 0%, more left than the target, and still a monthly amount', () => {
    const p = goalProgress({ ...goalById('g1'), target: 100000 }, -2500, TODAY);
    t.eq([p.percent, p.left, p.reached, p.plan], [0, 102500, false, { kind: 'monthly', perMonth: 34167, months: 3 }]);
    t.eq(goalSummary(p), '-₱25.00 of ₱1,000.00 · 0% saved');
  });

  // ------------------------------------------------------------ whole months and the monthly amount
  t.check('goals: whole months count calendar months, minus a part month, never below 1', () => {
    const cases = [
      ['2026-11-25', '2027-02-25', 3], ['2026-11-25', '2027-02-24', 2], ['2026-11-25', '2027-02-28', 3], ['2026-11-25', '2026-11-30', 1],
      ['2026-11-25', '2026-11-25', 1], ['2026-11-25', '2026-12-01', 1], ['2026-11-25', '2026-12-24', 1], ['2026-11-25', '2026-12-25', 1],
      ['2026-11-25', '2027-01-25', 2], ['2026-11-25', '2027-11-25', 12], ['2026-12-31', '2027-01-01', 1], ['2026-01-31', '2026-02-28', 1],
      ['2026-11-25', '2026-10-31', 1],
    ];
    for (const [from, to, months] of cases) t.eq([from, to, monthsLeft(from, to)], [from, to, months]);
  });
  t.check('goals: months are null for anything that is not a real date', () => {
    for (const bad of ['2027-02-30', 'soon', '', '2027-2-5', null, undefined, 20270225]) t.eq(monthsLeft('2026-11-25', bad), null, String(bad));
    t.eq(monthsLeft('2026-13-01', '2027-01-01'), null);
    t.eq([isDateText('2028-02-29'), isDateText('2027-02-29'), isDateText('2027-02-25T00:00'), isDateText(null)], [true, false, false, false]);
  });
  t.check('goals: needed per month rounds up so the payments always reach the goal', () => {
    t.eq([neededPerMonth(910000, 3), neededPerMonth(900000, 3), neededPerMonth(1, 12), neededPerMonth(500, 1)], [303334, 300000, 1, 500]);
    t.eq([neededPerMonth(0, 3), neededPerMonth(1000, 0)], [0, 0]);
  });
  t.check('goals: the plan is decided in order: wallet, no target, reached, no deadline, overdue, monthly', () => {
    t.eq(goalPlan(100000, null, '2027-02-25', TODAY), { kind: 'wallet' });
    t.eq(goalPlan(0, null, null, TODAY), { kind: 'wallet' });
    t.eq([goalPlan(0, 5, '2027-02-25', TODAY).kind, goalPlan(-1, 5, null, TODAY).kind], ['notarget', 'notarget']);
    t.eq([goalPlan(100000, 100000, '2026-01-01', TODAY).kind, goalPlan(100000, 250000, null, TODAY).kind], ['reached', 'reached']);
    t.eq([goalPlan(100000, 0, null, TODAY).kind, goalPlan(100000, 0, 'not a date', TODAY).kind], ['nodeadline', 'nodeadline']);
    t.eq([goalPlan(100000, 10, '2026-11-24', TODAY).kind, goalPlan(100000, 10, '2025-01-01', TODAY).kind], ['overdue', 'overdue']);
    t.eq(goalPlan(100000, 10, '2026-11-25', TODAY), { kind: 'monthly', perMonth: 99990, months: 1 }); // today
    t.eq(goalPlan(100000, 10, '2026-11-30', TODAY), { kind: 'monthly', perMonth: 99990, months: 1 }); // this month
    t.eq(goalPlan(100000, 99999, '2027-02-25', TODAY), { kind: 'monthly', perMonth: 1, months: 3 }); // one centavo short
  });

  // ------------------------------------------------------------ wallets and scope
  t.check('goals: an archived or unknown wallet means "unavailable", never a balance of 0 against the target', () => {
    t.eq(planCode(progressOf('g9').plan), 'WALLET');
    const gone = goalsProgress([{ ...goalById('g1'), wallet_id: 'nowhere' }], wallets, txns, TODAY)[0];
    t.eq([gone.saved, gone.left, gone.reached, planCode(gone.plan)], [0, 1200000, false, 'WALLET']);
  });
  t.check('goals: deleted goals never list, and a goal missing its deadline field reads as no deadline', () => {
    const list = [{ ...goalById('g1'), deleted: true }, { ...goalById('g3'), deadline: undefined }];
    t.eq(visibleGoals(list, new Set([ME])).map((g) => g.id), ['g3']);
    t.eq(planCode(goalsProgress([list[1]], wallets, txns, TODAY)[0].plan), 'NODEADLINE');
  });
  t.check('goals: dateLabel is the phone\'s Ledger.dateLabel and passes an unreadable date through', () => {
    t.eq([dateLabel('2027-02-25'), dateLabel('2026-11-03'), dateLabel('nope'), dateLabel(null)], ['Feb 25, 2027', 'Nov 3, 2026', 'nope', '']);
  });
  t.check('goals: no wording says a goal or saving is deductible (information, not advice)', () => {
    for (const p of progressAll()) {
      for (const text of [goalSummary(p), goalStatus(p), goalPlanText(p) || '', goalDeadlineText(p.goal) || '']) t.ok(!/deductib/i.test(text), text);
    }
  });
  t.check('goals: progressBar with warnAt null draws no warning tick, and the default still draws one (budgets)', () => {
    if (typeof document === 'undefined') return;
    t.eq(progressBar({ fraction: 0.5, tone: 'ok', warnAt: null }).querySelectorAll('line').length, 0);
    t.eq(progressBar({ fraction: 0.5, tone: 'ok' }).querySelectorAll('line').length, 1);
  });

  // ------------------------------------------------------------ data.js
  t.check('goals data: normalizeGoal makes numbers of money, null of an empty deadline, and a real boolean of deleted', () => {
    const g = normalizeGoal({ id: 'a', name: 'x', target: '2000000', deadline: '', updated_at: '5', deleted: null, wallet_id: 'w' });
    t.eq([g.target, g.deadline, g.updated_at, g.deleted, g.wallet_id], [2000000, null, 5, false, 'w']);
    t.eq(normalizeGoal({ deadline: '2027-02-25', deleted: true }).deleted, true);
  });
  t.later('goals data: loadGoals asks only for live goals of the given owners and keeps their database names', async () => {
    const rows = goals.map((g) => ({ ...g }));
    rows.push({ ...goalById('g1'), id: 'gone', deleted: true });
    rows.push({ ...goalById('g1'), id: 'stranger', owner_id: 'zed' });
    const client = fakeClient(rows);
    const mine = await loadGoals(client, [ME]);
    t.eq(mine.map((g) => g.id).sort(), GOALS.filter((g) => g[1] === 'me').map((g) => g[0]).sort());
    t.ok(client.calls.every((c) => c.table === 'goals'));
    t.eq(client.calls[0].eqs, [['deleted', false]]);
    t.eq(client.calls[0].ins, [['owner_id', [ME]]]);
    const both = await loadGoals(client, [ME, KIM]);
    t.ok(both.some((g) => g.id === 'g11') && !both.some((g) => g.id === 'stranger'));
  });
  t.later('goals data: loadGoals reads every page (not just the first 1000) and fails loudly on a bad page', async () => {
    const many = Array.from({ length: 1001 }, (_, i) => ({ id: `id${String(i).padStart(4, '0')}`, owner_id: ME, name: 'n', target: 1, deadline: null, wallet_id: 'w', updated_at: 1, deleted: false }));
    const client = fakeClient(many);
    t.eq((await loadGoals(client, [ME])).length, 1001);
    t.eq(client.calls.length, 2);
    let message = '';
    try { await loadGoals(fakeClient(many, { failOn: (q) => q.range && q.range[0] > 0 }), [ME]); } catch (e) { message = e.message; }
    t.eq(message, 'boom');
    t.eq((await fetchAllPages(async () => ({ data: [], error: null }))).length, 0);
  });
  t.later('goals data: the web never writes a goal (data.js exports no goal write)', async () => {
    const data = await import('./data.js');
    t.eq(Object.keys(data).filter((k) => /goal/i.test(k)).sort(), ['loadGoals', 'normalizeGoal']);
  });

  // ------------------------------------------------------------ the same vector as the phone's test (Node only)
  t.later('goals: this file\'s vector equals the one in GoalsTest.kt (wallets, txns, goals, expected numbers)', async () => {
    if (typeof process === 'undefined' || !process.versions || !process.versions.node) return; // a browser cannot read the file
    const fs = await import('node:fs/promises');
    const kt = await fs.readFile(new URL('../android/app/src/test/java/com/chimu/GoalsTest.kt', import.meta.url), 'utf8');
    const all = (re) => [...kt.matchAll(re)];
    const today = /private val today = "([\d-]+)"/.exec(kt);
    t.ok(today && today[1] === TODAY, 'today differs');
    t.eq(all(/wallet\("(\w+)", "(\w+)", WalletType\.(\w+), (\d+)(, deleted = true)?\)/g)
      .map((m) => [m[1], m[2], m[3].toLowerCase(), Number(m[4]), !!m[5]]), WALLETS, 'wallets differ');
    const ktTxns = all(/(?:transfer\("(t\d+)", "(\w+)", "(\w+)", "(\w+)", (\d+), "([\d-]+)"(, deleted = true)?\)|other\("(t\d+)", "(\w+)", TxnType\.(\w+), "(\w+)", (\d+), "([\d-]+)"\))/g)
      .map((m) => (m[1]
        ? [m[1], m[2], 'transfer', m[3], m[4], Number(m[5]), m[6], !!m[7]]
        : [m[8], m[9], m[10].toLowerCase(), m[11], null, Number(m[12]), m[13], false]));
    t.eq(ktTxns, TXNS, 'transactions differ');
    t.eq(all(/goal\("(g\d+)", "(\w+)", "([^"]+)", (\d+), (null|"[\d-]+"), "(\w+)"\)/g)
      .map((m) => [m[1], m[2], m[3], Number(m[4]), m[5] === 'null' ? null : m[5].slice(1, -1), m[6]]), GOALS, 'goals differ');
    t.eq(all(/"(g\d+)" to Expect\((\d+), (\d+), (\d+), (true|false), "([^"]+)"\)/g)
      .map((m) => [m[1], Number(m[2]), Number(m[3]), Number(m[4]), m[5] === 'true', m[6]]), EXPECT, 'expected numbers differ');
    const order = /listOf\(("g\d+"(?:, "g\d+")*)\), order\)/.exec(kt);
    t.ok(order, 'phone order line not found');
    t.eq(order[1].split(', ').map((s) => s.slice(1, -1)), PHONE_ORDER, 'phone order differs');
  });

  // ------------------------------------------------------------ the Savings goals card (needs a DOM)
  if (typeof document === 'undefined') return;

  // The dashboard keeps module-level state (resetDashboardPeriod clears the Budgets form). Earlier modules'
  // async checks use it across awaits, so these DOM checks wait for them and then run one after another.
  let chain = t.idle();
  const scenario = (name, fn) => t.later(name, () => { chain = chain.then(fn, fn); return chain; });

  const HOSTILE = '<img src=x onerror="window.__pwned=1">';
  const q = (root, sel) => root.querySelector(sel);
  const qa = (root, sel) => [...root.querySelectorAll(sel)];
  const texts = (root, sel) => qa(root, sel).map((n) => n.textContent);
  const click = (node) => node.dispatchEvent(new window.MouseEvent('click', { bubbles: true }));
  const flush = () => new Promise((resolve) => setTimeout(resolve, 0));
  const fresh = () => {
    resetDashboardPeriod();
    const root = document.createElement('div');
    document.body.append(root);
    return root;
  };
  const card = (root) => qa(root, '.dash-card').find((c) => q(c, 'h3').textContent === 'Savings goals');
  const ctxWallets = () => wallets.filter((w) => !w.deleted).map((w) => ({ ...w }));
  const personalCtx = (over = {}) => ({
    mode: 'personal', meId: ME, groupName: null, today: TODAY, groupId: null, myGroupIds: [],
    members: [{ id: ME, name: 'Matt' }], wallets: ctxWallets(), txns: txns.filter((x) => !x.deleted), goals: goals.map((g) => ({ ...g })), ...over,
  });
  const groupCtx = (over = {}) => ({
    mode: 'group', meId: ME, groupName: 'Family', today: TODAY, groupId: 'g1', myGroupIds: ['g1'],
    members: [{ id: ME, name: 'Matt' }, { id: KIM, name: 'Kim' }], wallets: ctxWallets(), txns: txns.filter((x) => !x.deleted),
    goals: goals.map((g) => ({ ...g })), ...over,
  });
  const byName = (root, name) => qa(card(root), '.dash-goal').find((li) => q(li, '.dash-goal-name').textContent === name);

  scenario('goals card: Personal lists my goals in order with amounts, words and wallet; kim\'s goal is not there', () => {
    const root = fresh();
    renderDashboard(root, personalCtx());
    t.eq(texts(card(root), '.dash-goal-name'), PHONE_ORDER.map((id) => goalById(id).name));
    const rows = qa(card(root), '.dash-goal');
    t.eq(texts(rows[6], '.dash-goal-text, .dash-goal-status, .dash-goal-line, .dash-goal-wallet'), [
      '₱9,100.00 left', '₱2,900.00 of ₱12,000.00 · 24% saved', 'Needed per month: ₱3,033.34 (3 months left)', 'Deadline: Feb 25, 2027', 'Savings wallet: w-wA',
    ]);
    t.ok(!card(root).textContent.includes('Kim'), 'kim\'s goal leaked into Personal');
    t.eq(qa(card(root), '.dash-goal-owner').length, 0);
    resetDashboardPeriod();
  });
  scenario('goals card: a reached goal says so in words and has its own bar tone; the bar of a goal past its target stops at the track end', () => {
    const root = fresh();
    renderDashboard(root, personalCtx());
    const phone = byName(root, 'Phone');
    t.eq(q(phone, '.dash-goal-status').textContent, 'Goal reached');
    t.eq(q(phone, '.dash-bar').getAttribute('class'), 'dash-bar dash-lvl-done');
    t.ok(q(phone, '.dash-bar').getAttribute('d').includes('H596Q600'), q(phone, '.dash-bar').getAttribute('d'));
    t.ok(q(byName(root, 'Baguio trip'), '.dash-bar').getAttribute('d').includes('H141Q145'), q(byName(root, 'Baguio trip'), '.dash-bar').getAttribute('d'));
    t.eq(q(byName(root, 'Baguio trip'), '.dash-bar').getAttribute('class'), 'dash-bar dash-lvl-ok');
    t.eq(qa(byName(root, 'No target'), '.dash-bar').length, 0); // saved against no target: no fill, the words still say it
    t.eq(qa(card(root), 'line.dash-progress-tick').length, 0);
    for (const svg of qa(card(root), 'svg.dash-progress')) t.eq(svg.getAttribute('aria-hidden'), 'true');
    resetDashboardPeriod();
  });
  scenario('goals card: an archived savings wallet reads "unavailable" (nothing guessed), and an overdue goal says the deadline passed', () => {
    const root = fresh();
    renderDashboard(root, personalCtx());
    t.eq(q(byName(root, 'Lost wallet goal'), '.dash-goal-status').textContent, 'Savings wallet unavailable');
    t.ok(!q(byName(root, 'Lost wallet goal'), '.dash-goal-wallet'));
    t.ok(byName(root, 'Old debt').textContent.includes('Deadline passed (Oct 31, 2026)'));
    resetDashboardPeriod();
  });
  scenario('goals card: Group lists every member\'s goals under their name, others marked read-only, using their wallets\' balances', () => {
    const root = fresh();
    renderDashboard(root, groupCtx());
    t.eq(texts(card(root), '.dash-goal-owner'), ['Matt (you)', 'Kimread-only']);
    const lists = qa(card(root), 'ul.dash-goals');
    t.eq(lists.length, 2);
    t.eq(texts(lists[0], '.dash-goal-name'), PHONE_ORDER.map((id) => goalById(id).name));
    t.eq(texts(lists[1], '.dash-goal-name'), ['Kim\'s bike']);
    t.eq(texts(lists[1], '.dash-goal-text, .dash-goal-status, .dash-goal-line'), [
      '₱7,000.00 left', '₱3,000.00 of ₱10,000.00 · 30% saved', 'Needed per month: ₱1,166.67 (6 months left)', 'Deadline: May 25, 2027',
    ]);
    resetDashboardPeriod();
  });
  scenario('goals card: it is read-only: no form, input, select, textarea or button, in Personal or Group', () => {
    for (const ctx of [personalCtx(), groupCtx()]) {
      const root = fresh();
      renderDashboard(root, ctx);
      t.eq(qa(card(root), 'form, input, select, textarea, button').length, 0);
      resetDashboardPeriod();
    }
  });
  scenario('goals card: other groups\' people and deleted goals never show, even if the list holds them', () => {
    const root = fresh();
    const extra = [{ ...goalById('g1'), id: 'zed-goal', owner_id: 'zed', name: 'Zed secret' }, { ...goalById('g1'), id: 'del', deleted: true, name: 'Deleted one' }];
    renderDashboard(root, groupCtx({ goals: [...goals, ...extra] }));
    t.ok(!card(root).textContent.includes('Zed secret') && !card(root).textContent.includes('Deleted one'));
    resetDashboardPeriod();
  });
  scenario('goals card: names from the database are text, never markup', () => {
    const root = fresh();
    window.__pwned = 0;
    renderDashboard(root, personalCtx({ goals: [{ ...goalById('g1'), name: HOSTILE }] }));
    t.eq(texts(card(root), '.dash-goal-name'), [HOSTILE]);
    t.eq(qa(card(root), 'img').length, 0);
    t.eq(window.__pwned, 0);
    resetDashboardPeriod();
  });
  scenario('goals card: empty states say what to do (Personal) or that nobody has a goal (Group); a ctx without goals does not crash', () => {
    let root = fresh();
    renderDashboard(root, personalCtx({ goals: [] }));
    t.eq(texts(card(root), '.dash-none'), ['You have no savings goals yet. Add one on your phone: More, then Goals.']);
    resetDashboardPeriod();
    root = fresh();
    renderDashboard(root, groupCtx({ goals: [] }));
    t.eq(texts(card(root), '.dash-none'), ['No member has a savings goal yet.']);
    resetDashboardPeriod();
    root = fresh();
    renderDashboard(root, personalCtx({ goals: undefined }));
    t.eq(texts(card(root), '.dash-none').length, 1);
    resetDashboardPeriod();
  });
  scenario('goals card: a failed load says so (never "no goals") and Try again calls the host once', async () => {
    const root = fresh();
    let retries = 0;
    renderDashboard(root, personalCtx({ goals: [], goalsError: 'boom', onRetryGoals: () => { retries += 1; } }));
    t.ok(card(root).textContent.includes('Goals could not be loaded: boom'));
    t.eq(qa(card(root), '.dash-none').length, 0);
    const retry = q(card(root), 'button');
    click(retry);
    await flush();
    t.eq([retries, retry.disabled], [1, true]);
    resetDashboardPeriod();
  });
  scenario('goals card: an empty period (no spending or income at all) still shows the card, and the picked period never changes goals', () => {
    const root = fresh();
    renderDashboard(root, personalCtx({ txns: [], today: '2027-05-10' }));
    t.ok(card(root), 'the Savings goals card is missing from the empty layout');
    t.eq(texts(card(root), '.dash-goal-name').length, 12);
    resetDashboardPeriod();
  });
  scenario('goals card: it comes after budgets and before the charts and wallets, and is a labelled list', () => {
    const root = fresh();
    renderDashboard(root, personalCtx());
    const titles = qa(root, '.dash-card > h3').map((h) => h.textContent);
    t.ok(titles.indexOf('Budgets vs actual') < titles.indexOf('Savings goals'), titles.join('|'));
    t.ok(titles.indexOf('Savings goals') < titles.indexOf('Wallet balances'), titles.join('|'));
    t.eq(card(root).getAttribute('aria-labelledby'), q(card(root), 'h3').id);
    t.eq(q(card(root), 'ul.dash-goals').getAttribute('aria-label'), 'Savings goals');
    resetDashboardPeriod();
  });
}
