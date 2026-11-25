// Budget math and rules (RFC-009). Pure functions: no DOM, no network, money in integer centavos.
// Shared contract with the phone's Budgets.kt (BudgetRules / BudgetText); the parity vector in
// test-budgets.js is the same one BudgetsTest.kt asserts, so the two screens show the same usage.
//
// Definitions:
//   * a budget is { id, owner_id, group_id (null = personal), category (null = overall), monthly_limit }
//     with the database column names, like every other row on this page;
//   * usage for a month ('YYYY-MM') = sum of `amount` over live expense rows dated in that month, compared
//     on the first 7 characters of the date text (never through Date, so no time-zone shift);
//   * category null counts every expense; 'other' counts rows whose category is null or not a known
//     expense slug (stats.expenseCategory, the same rule as the category bars and the phone);
//   * a personal budget counts only its owner's rows; a group budget counts the rows of every member;
//   * percent = floor(spent * 100 / limit), NOT rounded: 79.9% is not 80;
//   * level: OK below 80%, WARN from 80%, OVER from 100%; a limit <= 0 means "unset": 0% and OK.
import { normCentavos, parsePesos, formatPesos } from './money.js';
import { CATEGORIES, categoryLabel } from './categories.js';
import { MONTH_NAMES, expenseCategory, isLive } from './stats.js';

export const WARN_PERCENT = 80;
export const OVER_PERCENT = 100;
export const LEVEL = Object.freeze({ OK: 'OK', WARN: 'WARN', OVER: 'OVER' });

const EXPENSE_SLUGS = new Set(CATEGORIES.expense.map((c) => c.slug));

const categoryOf = (budget) => budget.category || null; // '' and undefined mean "overall" too
const groupOf = (budget) => budget.group_id || null;

// --- usage ------------------------------------------------------------------------------------------

// Total spent against `budget` in `month` ('YYYY-MM'). `memberIds` matters only for a group budget:
// the ids of everyone in that group. A personal budget ignores it and counts its owner's rows.
export function budgetSpent(budget, txns, month, memberIds) {
  const owners = groupOf(budget) ? new Set(memberIds || []) : new Set([budget.owner_id]);
  const category = categoryOf(budget);
  let spent = 0;
  for (const t of txns || []) {
    if (!isLive(t) || t.type !== 'expense' || !owners.has(t.owner_id)) continue;
    if (typeof t.date !== 'string' || t.date.slice(0, 7) !== month) continue;
    if (category !== null && expenseCategory(t.category) !== category) continue;
    spent += normCentavos(t.amount);
  }
  return spent;
}

// Whole percent, rounded DOWN, in exact integer arithmetic (BigInt), so the answer never depends on float rounding.
export function percentOf(spent, limit) {
  if (!(limit > 0)) return 0;
  return Number((BigInt(spent) * 100n) / BigInt(limit));
}

export function levelOf(spent, limit) {
  const percent = percentOf(spent, limit);
  if (percent >= OVER_PERCENT) return LEVEL.OVER;
  if (percent >= WARN_PERCENT) return LEVEL.WARN;
  return LEVEL.OK;
}

// How full the bar is, 0..1: it stops at 100% width while the text keeps the true percent.
export function barFraction(spent, limit) {
  if (!(limit > 0)) return 0;
  return Math.min(1, Math.max(0, spent / limit));
}

export function budgetUsage(budget, txns, month, memberIds) {
  const limit = normCentavos(budget.monthly_limit);
  const spent = budgetSpent(budget, txns, month, memberIds);
  return {
    budget,
    spent,
    limit,
    percent: percentOf(spent, limit),
    level: levelOf(spent, limit),
    remaining: limit - spent, // negative when over
    barFraction: barFraction(spent, limit),
  };
}

// Usage for each budget in the order given.
export function usageList(budgets, txns, month, memberIds) {
  return (budgets || []).map((b) => budgetUsage(b, txns, month, memberIds));
}

// --- which budgets are shown, and in what order -------------------------------------------------------

// Personal first, then groups (by id); within a scope overall first, then by category label, then id.
function byDisplayOrder(a, b) {
  const lower = (s) => String(s).toLowerCase();
  const ga = groupOf(a) || '';
  const gb = groupOf(b) || '';
  if ((ga === '') !== (gb === '')) return ga === '' ? -1 : 1;
  if (ga !== gb) return ga < gb ? -1 : 1;
  const ca = categoryOf(a);
  const cb = categoryOf(b);
  if ((ca === null) !== (cb === null)) return ca === null ? -1 : 1;
  const la = lower(categoryLabel(ca));
  const lb = lower(categoryLabel(cb));
  if (la !== lb) return la < lb ? -1 : 1;
  return a.id < b.id ? -1 : a.id > b.id ? 1 : 0;
}

// The live budgets the dashboard shows for `scope` ({ mode, groupId }) and the signed-in person `me`.
// Personal view: my budgets with no group. Group view: every budget of that group, whoever owns it.
// (RLS also lets a person read other members' personal budgets and budgets of other groups; those are
// never shown here.)
export function visibleBudgets(budgets, scope, me) {
  const inGroup = scope && scope.mode === 'group';
  return (budgets || [])
    .filter((b) => b && b.deleted !== true
      && (inGroup ? groupOf(b) === scope.groupId : groupOf(b) === null && b.owner_id === me))
    .sort(byDisplayOrder);
}

// --- the month a period shows --------------------------------------------------------------------------

// The dashboard's selected month. In year view: the current month if that year is the current year,
// else December. `note` says so in words (null in month view). today = 'YYYY-MM-DD'.
export function budgetMonth(period, today) {
  const make = (year, month, note) => ({ month: `${year}-${String(month).padStart(2, '0')}`, label: `${MONTH_NAMES[month - 1]} ${year}`, note });
  if (period.kind === 'month') return make(period.year, period.month, null);
  if (period.year === Number(today.slice(0, 4))) {
    const label = `${MONTH_NAMES[Number(today.slice(5, 7)) - 1]} ${period.year}`;
    return make(period.year, Number(today.slice(5, 7)), `Year view: budgets are monthly, so this shows ${label}, the current month.`);
  }
  return make(period.year, 12, `Year view: budgets are monthly, so this shows December ${period.year}, the last month of that year.`);
}

// --- validation ------------------------------------------------------------------------------------------

// draft = { me, groupId (null = personal), category ('' or null = overall), limit (what was typed), editingId }.
// existing = the budgets already loaded; myGroupIds = ids of the groups I am in.
// Returns { errors: { category?, limit?, scope? }, budget } where budget = { groupId, category, monthlyLimit }
// is null when there are errors. Typed pesos go through the same strict parser as every other amount.
export function checkBudget(draft, existing, myGroupIds) {
  const errors = {};
  const groupId = draft.groupId || null;
  const category = draft.category === undefined || draft.category === '' ? null : draft.category;

  if (category !== null && !EXPENSE_SLUGS.has(category)) errors.category = 'Choose Overall or one of the expense categories.';
  if (groupId !== null && !(myGroupIds || []).includes(groupId)) errors.scope = 'You can only set a budget for a group you are in.';

  const limit = parsePesos(typeof draft.limit === 'string' ? draft.limit : '');
  if (limit === null) errors.limit = 'Enter a limit like 5,000 or 1,234.50 (at most 2 decimals).';
  else if (limit <= 0) errors.limit = 'The limit must be more than ₱0.00.';

  if (!errors.category && !errors.scope) {
    const clash = (existing || []).some((b) => b && b.deleted !== true && b.owner_id === draft.me
      && groupOf(b) === groupId && categoryOf(b) === category && b.id !== draft.editingId);
    if (clash) {
      const what = category === null ? 'an overall' : `a ${categoryLabel(category)}`;
      errors.category = `You already have ${what} budget ${groupId === null ? 'for yourself' : 'in this group'}. Edit that one instead of adding another.`;
    }
  }

  if (Object.keys(errors).length) return { errors, budget: null };
  return { errors, budget: { groupId, category, monthlyLimit: limit } };
}

// --- words ------------------------------------------------------------------------------------------------

export function budgetTitle(budget) {
  const category = categoryOf(budget);
  return category === null ? 'Overall' : categoryLabel(category);
}

// "₱4,800.00 of ₱6,000.00 · 80% used"
export function budgetSummary(usage) {
  return `${formatPesos(usage.spent)} of ${formatPesos(usage.limit)} · ${usage.percent}% used`;
}

// The state in words, so it never depends on colour. Exactly at the limit is not "over by ₱0.00".
export function budgetStatus(usage) {
  if (!(usage.limit > 0)) return 'No limit set';
  if (usage.level === LEVEL.OVER) {
    return usage.spent > usage.limit ? `Over the limit by ${formatPesos(usage.spent - usage.limit)}` : 'At the limit';
  }
  return usage.level === LEVEL.WARN ? 'Close to the limit' : 'On track';
}
