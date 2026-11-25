// The Dashboard tab (RFC-008): stat tiles, monthly and per-category bars, and for a group the
// per-member comparison. Everything is computed here from the rows the transactions list already has
// (ctx.txns); this file never queries Supabase and never edits data.
//
// renderDashboard(root, ctx) is called on every tab show and after every data refresh, so it clears
// and rebuilds `root` each time. The only thing it remembers between calls is the period picker.
//
//   ctx = { mode: 'personal' | 'group', meId, groupName,
//           members: [{ id, name }],
//           wallets: [{ id, owner_id, name, type, opening_balance, deleted }],
//           txns: [raw Supabase rows, all time, not deleted],
//           today?: 'YYYY-MM-DD',    // only for tests; the real date is used when it is missing
//           // Budgets vs actual (RFC-009). All optional; without the callbacks the card is read-only.
//           groupId?, myGroupIds?: [ids of the groups I am in],
//           budgets?: [budget rows of this scope, not deleted], budgetsError?: 'why they did not load',
//           onSaveBudget?({ id|null, groupId, category, monthlyLimit }), onDeleteBudget?(id),
//           onRetryBudgets?(),
//           // Savings goals (RFC-011), read-only. Optional; goals have no group column, so these are the goals of
//           // everyone in `members` (just me in Personal view).
//           goals?: [goal rows of this scope, not deleted], goalsError?: 'why they did not load', onRetryGoals?() }
//   The two save/delete callbacks do the write (app.js wires them to data.js) and answer
//   { ok: true, budgets } or { ok: false, message, budgets? } with the host's new list of budgets, so this
//   file still never talks to Supabase.
//
// All text from the data (names, categories) goes in through textContent, never innerHTML.
import { formatPesos, pesosPlain } from './money.js';
import { CATEGORIES, categoryLabel } from './categories.js';
import {
  WARN_PERCENT, budgetMonth, budgetStatus, budgetSummary, budgetTitle, checkBudget, usageList, visibleBudgets,
} from './budgets.js';
import {
  MONTH_NAMES, MONTH_SHORT, summarize, inPeriod, monthlySpend, spendByCategory, memberStats,
  monthlyByMember, walletBalances, periodsAvailable, periodOptions, periodKey, periodLabel,
  goalsProgress, visibleGoals,
} from './stats.js';
import { goalSummary, goalStatus, goalPlanText, goalDeadlineText } from './goals.js';
import {
  el, columnChart, stackedColumnChart, barList, legend, table, dataTable, percentText, progressBar,
} from './charts.js';

// The picker survives re-renders. year/month stay null until the person chooses, which means "the
// current month". `month` is remembered while in year mode so switching back returns to it.
let picker = { kind: 'month', year: null, month: null };

// Forget the chosen period (back to "this month"). RFC-007 can call this on sign-out so the next
// person does not inherit the last picker; the tests call it between checks.
export function resetDashboardPeriod() {
  picker = { kind: 'month', year: null, month: null };
  budgetUi = freshBudgetUi(null);
}

// What the Budgets card remembers between renders, so a data refresh never wipes what someone is typing
// (RULES 20). form = null | { editingId, category, limit, errors, formError }.
let budgetUi = freshBudgetUi(null);
function freshBudgetUi(scopeKey) {
  return { scopeKey, form: null, confirmingId: null, confirmError: '', status: '', busy: false };
}

// The newest renderDashboard() arguments: a write that finishes after a redraw must draw with these.
const live = { root: null, ctx: null };

const WALLET_TYPES = {
  cash: 'Cash', gcash: 'GCash', maya: 'Maya', card: 'Card', bank: 'Bank', savings: 'Savings',
};
const MAX_SLOTS = 8; // the palette has 8 hues; with more members the tail folds into "Other members"

const pad2 = (n) => String(n).padStart(2, '0');

function todayString() {
  const d = new Date(); // local date parts: the person's day, not UTC's
  return `${d.getFullYear()}-${pad2(d.getMonth() + 1)}-${pad2(d.getDate())}`;
}

// --- what the dashboard looks at ---------------------------------------------------------------------

// Narrows ctx to the rows this mode may show. Personal = my own rows. Group = the members' rows.
// (RLS also lets a person read rows from people in other groups, so the list is filtered here too.)
function scopeOf(ctx) {
  const group = ctx.mode === 'group';
  const me = ctx.meId;
  const members = (ctx.members || []).filter((m) => m && m.id !== undefined && m.id !== null).map((m) => ({
    id: m.id,
    name: `${String(m.name || '').trim() || 'A member'}${m.id === me ? ' (you)' : ''}`,
  }));
  const ids = new Set(members.map((m) => m.id));
  const mine = (ownerId) => (group ? ids.has(ownerId) : !me || ownerId === me);
  return {
    group,
    members,
    txns: (ctx.txns || []).filter((t) => t && mine(t.owner_id)),
    wallets: (ctx.wallets || []).filter((w) => w && mine(w.owner_id)),
    allTxns: ctx.txns || [],
  };
}

// Colors follow the person, not the row: slots are handed out by member id, so a filter or a new
// member never repaints the others. Past 8 members the tail shares the gray "other" slot.
function assignSeries(members) {
  const order = [...members].sort((a, b) => (String(a.id) < String(b.id) ? -1 : String(a.id) > String(b.id) ? 1 : 0));
  const fold = order.length > MAX_SLOTS;
  const byId = new Map();
  order.forEach((m, i) => byId.set(m.id, fold && i >= MAX_SLOTS - 1 ? { slot: 'other', tag: '+' } : { slot: i + 1, tag: String(i + 1) }));
  return byId;
}

// --- period picker ---------------------------------------------------------------------------------

function selectedPeriod(today) {
  const year = picker.year ?? Number(today.slice(0, 4));
  if (picker.kind === 'year') return { kind: 'year', year };
  return { kind: 'month', year, month: picker.month ?? (picker.year === null ? Number(today.slice(5, 7)) : 12) };
}

// The month to land on when switching to month view of `year`: this month if it is this year, else
// the latest month that has data, else December.
function monthFor(year, today, txns) {
  if (year === Number(today.slice(0, 4))) return Number(today.slice(5, 7));
  const latest = periodsAvailable(txns).months.find((m) => m.startsWith(`${year}-`));
  return latest ? Number(latest.slice(5, 7)) : 12;
}

function choose(period) {
  picker = { kind: period.kind, year: period.year, month: period.kind === 'month' ? period.month : picker.month };
}

function buildControls(period, options, view, today, rerender) {
  const idx = options.findIndex((o) => periodKey(o) === periodKey(period));
  const older = options[idx + 1];
  const newer = options[idx - 1];
  const noun = period.kind === 'year' ? 'year' : 'month';

  const kindButton = (kind, text) => {
    const b = el('button', { type: 'button', class: 'dash-btn', 'aria-pressed': String(period.kind === kind), 'data-dash-focus': `kind-${kind}` }, text);
    b.addEventListener('click', () => {
      if (period.kind === kind) return;
      picker = { kind, year: period.year, month: period.kind === 'month' ? period.month : (picker.month ?? monthFor(period.year, today, view.txns)) };
      rerender(`kind-${kind}`);
    });
    return b;
  };
  const step = (target, text, focusKey, aria) => {
    const b = el('button', { type: 'button', class: 'dash-btn', 'aria-label': aria, 'data-dash-focus': focusKey, disabled: target ? null : '' }, text);
    if (target) b.addEventListener('click', () => { choose(target); rerender(focusKey); });
    return b;
  };

  const select = el('select', { class: 'dash-select', id: 'dash-period', 'data-dash-focus': 'select' },
    options.map((o) => el('option', { value: periodKey(o), selected: periodKey(o) === periodKey(period) ? '' : null }, periodLabel(o))));
  select.addEventListener('change', () => {
    const chosen = options.find((o) => periodKey(o) === select.value);
    if (chosen) { choose(chosen); rerender('select'); }
  });

  return el('div', { class: 'dash-controls' }, [
    el('div', { class: 'dash-toggle', role: 'group', 'aria-label': 'Show by' }, [kindButton('month', 'Month'), kindButton('year', 'Year')]),
    step(older, '‹ Earlier', 'prev', `Earlier ${noun}`),
    el('label', { class: 'dash-selectwrap', for: 'dash-period' }, [el('span', { class: 'dash-sr' }, `Choose a ${noun}`), select]),
    step(newer, 'Later ›', 'next', `Later ${noun}`),
  ]);
}

// --- sections --------------------------------------------------------------------------------------

function card(title, note) {
  const id = `dash-h-${title.toLowerCase().replace(/[^a-z0-9]+/g, '-')}`;
  const section = el('section', { class: 'dash-card', 'aria-labelledby': id }, [el('h3', { id }, title)]);
  if (note) section.append(el('p', { class: 'dash-note' }, note));
  return section;
}

function plainNote(text) {
  return el('p', { class: 'dash-none' }, text);
}

function buildTiles(summary, label) {
  const tile = (name, value, note) => el('div', { class: 'dash-tile' }, [
    el('dt', {}, name), el('dd', { class: 'dash-tile-value' }, value), el('dd', { class: 'dash-tile-note' }, note),
  ]);
  const net = summary.net > 0 ? `+${formatPesos(summary.net)}` : formatPesos(summary.net);
  const dl = el('dl', { class: 'dash-tiles', 'aria-label': `Totals for ${label}` }, [
    tile('Spent', formatPesos(summary.spent), 'Expenses only'),
    tile('Income', formatPesos(summary.income), 'Money received'),
    tile('Net', net, 'Income minus spent'),
    tile('VAT paid (from receipts)', formatPesos(summary.vat), 'On expense entries'),
    tile('Receipts', String(summary.receipts), summary.receipts === 1 ? 'Entry with a photo' : 'Entries with a photo'),
  ]);
  return dl;
}

function buildMonthly(view, period) {
  const year = period.year;
  const buckets = monthlySpend(view.txns, year);
  const total = buckets.reduce((sum, b) => sum + b.spent, 0);
  const section = card(`Spending by month, ${year}`, period.kind === 'month' ? 'Expenses only. The selected month is highlighted.' : 'Expenses only.');
  if (total === 0) {
    section.append(plainNote(`No spending is logged for ${year}.`));
    return section;
  }
  const best = buckets.reduce((b, cur, i) => (cur.spent > buckets[b].spent ? i : b), 0);
  const selected = period.kind === 'month' ? period.month - 1 : -1;
  const summary = `Spending by month for ${year}. Total ${formatPesos(total)}. Highest: ${MONTH_NAMES[best]}, ${formatPesos(buckets[best].spent)}.`
    + (selected >= 0 ? ` ${MONTH_NAMES[selected]} is highlighted.` : '');
  section.append(
    columnChart({
      label: summary,
      items: buckets.map((b, i) => ({ short: MONTH_SHORT[i], name: `${MONTH_NAMES[i]} ${year}`, value: b.spent })),
      highlight: selected,
    }),
    dataTable({
      caption: `Spending by month, ${year}`,
      headers: ['Month', 'Spent'],
      rows: [...buckets.map((b, i) => [MONTH_NAMES[i], formatPesos(b.spent)]), ['Total', formatPesos(total)]],
      numeric: [false, true],
    }),
  );
  return section;
}

function buildCategories(rows, summary, label) {
  const section = card('Spending by category', label);
  const cats = spendByCategory(rows);
  if (cats.length === 0) {
    section.append(plainNote(`No spending is logged for ${label}.`));
    return section;
  }
  const names = `Spending by category, ${label}. Largest: ${cats[0].label}, ${formatPesos(cats[0].spent)}.`;
  section.append(
    barList({
      label: names,
      rows: cats.map((c) => ({ name: c.label, value: c.spent, detail: percentText(c.spent, summary.spent) })),
    }),
    dataTable({
      caption: `Spending by category, ${label}`,
      headers: ['Category', 'Spent', 'Share'],
      rows: cats.map((c) => [c.label, formatPesos(c.spent), percentText(c.spent, summary.spent)]),
      numeric: [false, true, true],
    }),
  );
  return section;
}

function buildMembers(view, rows, label, ids) {
  const section = card('Members compared', label);
  const stats = memberStats(rows, view.members)
    .sort((a, b) => (b.spent - a.spent) || (a.name.toLowerCase() < b.name.toLowerCase() ? -1 : a.name.toLowerCase() > b.name.toLowerCase() ? 1 : 0));
  const top = stats[0];
  section.append(
    barList({
      label: `Spending per member, ${label}. Most: ${top.name}, ${formatPesos(top.spent)}.`,
      rows: stats.map((s) => ({ name: s.name, value: s.spent, ...ids.get(s.id) })),
    }),
    table({
      caption: `Spent, income and top spending category per member, ${label}`,
      headers: ['Member', 'Spent', 'Income', 'Top category'],
      rows: stats.map((s) => [
        s.name, formatPesos(s.spent), formatPesos(s.income),
        s.topCategory ? `${s.topCategory.label}, ${formatPesos(s.topCategory.spent)}` : 'No spending',
      ]),
      numeric: [false, true, true, false],
    }),
  );
  return section;
}

function buildStacked(view, year, highlight, ids) {
  const section = card(`Monthly spending by member, ${year}`, 'Expenses only. Each member has a number and a color.');
  const per = monthlyByMember(view.txns, view.members, year).map((m) => ({ ...m, ...ids.get(m.id) }));
  const total = per.reduce((sum, m) => sum + m.months.reduce((a, b) => a + b, 0), 0);
  if (total === 0) {
    section.append(plainNote(`No spending is logged for ${year}.`));
    return section;
  }
  const series = per.filter((m) => m.slot !== 'other').sort((a, b) => a.slot - b.slot)
    .map((m) => ({ name: m.name, slot: m.slot, tag: m.tag, values: m.months }));
  const rest = per.filter((m) => m.slot === 'other');
  if (rest.length) {
    series.push({ name: 'Other members', slot: 'other', tag: '+', values: MONTH_NAMES.map((_, i) => rest.reduce((sum, m) => sum + m.months[i], 0)) });
  }
  const categories = MONTH_NAMES.map((name, i) => ({ short: MONTH_SHORT[i], name: `${name} ${year}` }));
  section.append(
    legend(series),
    stackedColumnChart({
      label: `Monthly spending per member for ${year}, stacked. Total ${formatPesos(total)}.`,
      categories, series, highlight,
    }),
    dataTable({
      caption: `Monthly spending per member, ${year}`,
      headers: ['Month', ...series.map((s) => s.name), 'Total'],
      rows: MONTH_NAMES.map((name, i) => [
        name, ...series.map((s) => formatPesos(s.values[i])), formatPesos(series.reduce((sum, s) => sum + s.values[i], 0)),
      ]),
      numeric: [false, ...series.map(() => true), true],
    }),
  );
  return section;
}

// The wallet's type in words, unless the name already says it ("Cash" / Cash).
function walletType(w) {
  const type = WALLET_TYPES[w.type] || String(w.type || '');
  return type && type.toLowerCase() !== String(w.name).trim().toLowerCase() ? el('span', { class: 'dash-wallet-type' }, type) : '';
}

function walletList(list) {
  return el('ul', { class: 'dash-wallets' }, list.map((w) => el('li', {}, [
    el('span', { class: 'dash-wallet-name' }, [el('span', {}, w.name), walletType(w)]),
    el('span', { class: 'dash-wallet-balance' }, formatPesos(w.balance)),
  ])));
}

// Balances are over ALL time (DESIGN.md), not the selected period.
function buildWallets(view) {
  const section = card('Wallet balances', 'Balance now: opening balance plus everything logged, not just this period.');
  const balances = walletBalances(view.wallets, view.allTxns);
  if (balances.length === 0) {
    section.append(plainNote('No wallets yet.'));
    return section;
  }
  if (!view.group) {
    section.append(walletList(balances));
    return section;
  }
  let shown = 0;
  for (const m of view.members) {
    const mine = balances.filter((w) => w.owner_id === m.id);
    if (mine.length === 0) continue;
    shown += 1;
    section.append(el('h4', { class: 'dash-wallet-owner' }, m.name), walletList(mine));
  }
  if (shown === 0) section.append(plainNote('No wallets yet.'));
  return section;
}

function buildEmpty(view, period, today, rerender) {
  const label = periodLabel(period);
  const box = el('section', { class: 'dash-empty', role: 'status' }, [
    el('h3', {}, `Nothing logged for ${label}`),
    el('p', {}, view.group ? 'No member has spending or income in this period.' : 'There is no spending or income in this period.'),
  ]);
  const avail = periodsAvailable(view.txns);
  const latest = period.kind === 'year'
    ? (avail.years[0] === undefined ? null : { kind: 'year', year: avail.years[0] })
    : (avail.months[0] === undefined ? null : { kind: 'month', year: Number(avail.months[0].slice(0, 4)), month: Number(avail.months[0].slice(5, 7)) });
  if (latest && periodKey(latest) !== periodKey(period)) {
    const go = el('button', { type: 'button', class: 'dash-btn dash-btn-accent' }, `Show ${periodLabel(latest)}`);
    go.addEventListener('click', () => { choose(latest); rerender('select'); });
    box.append(go);
  }
  return box;
}

// --- budgets vs actual (RFC-009) ------------------------------------------------------------------------

// Draws again with `patch` merged into the newest ctx, then moves focus to the element tagged `focusKey`.
function redraw(focusKey, patch = {}) {
  live.ctx = { ...live.ctx, ...patch };
  renderDashboard(live.root, live.ctx);
  const target = focusKey && live.root.querySelector(`[data-dash-focus="${focusKey}"]:not([disabled])`);
  if (target) target.focus();
}

// Draws again after a write: the host's new list of budgets, if it sent one, replaces ours.
function afterWrite(res, focusKey) {
  redraw(focusKey, Array.isArray(res.budgets) ? { budgets: res.budgets } : {});
}

async function runWrite(call) {
  try {
    const res = await call();
    return res && typeof res.ok === 'boolean' ? res : { ok: false, message: 'The change could not be saved.' };
  } catch (e) {
    return { ok: false, message: (e && e.message) || String(e) };
  }
}

function budgetScopeText(ctx, budget) {
  return budget.group_id ? `Group: ${ctx.groupName || 'your group'}` : 'Personal';
}

function budgetRow(ctx, usage, canEdit) {
  const b = usage.budget;
  const mine = b.owner_id === ctx.meId;
  const title = budgetTitle(b);
  const tone = usage.level.toLowerCase();
  const ownerName = (ctx.members || []).find((m) => m.id === b.owner_id);
  const li = el('li', { class: `dash-budget dash-budget-${tone}` }, [
    el('div', { class: 'dash-budget-head' }, [
      el('span', { class: 'dash-budget-name' }, title),
      el('span', { class: 'dash-budget-scope' }, budgetScopeText(ctx, b)),
      el('span', { class: 'dash-budget-status' }, budgetStatus(usage)),
    ]),
    progressBar({ fraction: usage.barFraction, tone, warnAt: WARN_PERCENT / 100 }),
    el('p', { class: 'dash-budget-text' }, budgetSummary(usage)),
  ]);
  if (!mine || ctx.mode === 'group') {
    li.append(el('p', { class: 'dash-budget-by' }, `Set by ${mine ? 'you' : (ownerName && String(ownerName.name || '').trim()) || 'another member'}`));
  }
  if (!mine || !canEdit) return li;

  const edit = el('button', { type: 'button', class: 'dash-btn', 'aria-label': `Edit ${title} budget`, 'data-dash-focus': `budget-edit-${b.id}` }, 'Edit');
  edit.addEventListener('click', () => {
    if (budgetUi.busy) return;
    budgetUi.confirmingId = null;
    budgetUi.confirmError = '';
    budgetUi.status = '';
    budgetUi.form = { editingId: b.id, category: b.category || '', limit: b.monthly_limit > 0 ? pesosPlain(b.monthly_limit) : '', errors: {}, formError: '' };
    redraw('budget-category');
  });
  const del = el('button', { type: 'button', class: 'dash-btn', 'aria-label': `Delete ${title} budget`, 'data-dash-focus': `budget-delete-${b.id}` }, 'Delete');
  del.addEventListener('click', () => {
    if (budgetUi.busy) return;
    budgetUi.confirmingId = b.id;
    budgetUi.confirmError = '';
    budgetUi.status = '';
    redraw(`budget-no-${b.id}`);
  });
  li.append(el('div', { class: 'dash-budget-actions' }, [edit, del]));

  if (budgetUi.confirmingId === b.id) {
    const yes = el('button', { type: 'button', class: 'dash-btn dash-btn-danger', disabled: budgetUi.busy ? '' : null, 'data-dash-focus': `budget-yes-${b.id}` }, 'Yes, delete it');
    const no = el('button', { type: 'button', class: 'dash-btn', disabled: budgetUi.busy ? '' : null, 'data-dash-focus': `budget-no-${b.id}` }, 'Cancel');
    no.addEventListener('click', () => {
      if (budgetUi.busy) return;
      budgetUi.confirmingId = null;
      budgetUi.confirmError = '';
      redraw(`budget-delete-${b.id}`);
    });
    yes.addEventListener('click', async () => {
      if (budgetUi.busy) return;
      budgetUi.busy = true;
      yes.disabled = true;
      no.disabled = true;
      const res = await runWrite(() => ctx.onDeleteBudget(b.id));
      budgetUi.busy = false;
      if (res.ok) {
        budgetUi.confirmingId = null;
        budgetUi.confirmError = '';
        budgetUi.status = `Deleted the ${title} budget. It leaves the phone after its next sync.`;
        afterWrite(res, 'budget-add');
      } else {
        budgetUi.confirmError = `${res.message} The budget was not deleted.`;
        afterWrite(res, `budget-no-${b.id}`);
      }
    });
    li.append(el('div', { class: 'dash-confirm', role: 'group', 'aria-label': `Delete the ${title} budget` }, [
      el('p', {}, `Delete the ${title} budget? It leaves the totals here and on the phone after its next sync.`),
      budgetUi.confirmError ? el('p', { class: 'dash-b-error', role: 'alert' }, budgetUi.confirmError) : null,
      el('div', { class: 'dash-budget-actions' }, [yes, no]),
    ].filter(Boolean)));
  }
  return li;
}

// The add / edit form. Typed text lives in budgetUi.form, not only in the inputs, so a redraw keeps it.
function budgetForm(ctx, scope) {
  const f = budgetUi.form;
  const editing = f.editingId !== null;
  const known = CATEGORIES.expense.some((c) => c.slug === f.category);
  const options = [
    el('option', { value: '', selected: f.category === '' ? '' : null }, 'Overall (all expenses)'),
    ...CATEGORIES.expense.map((c) => el('option', { value: c.slug, selected: f.category === c.slug ? '' : null }, c.label)),
  ];
  if (f.category !== '' && !known) options.push(el('option', { value: f.category, selected: '' }, categoryLabel(f.category)));

  const invalid = (name) => (f.errors[name] ? 'true' : null);
  const select = el('select', {
    class: 'dash-select dash-b-input', id: 'dash-b-category', 'data-dash-focus': 'budget-category',
    'aria-describedby': 'dash-b-category-err', 'aria-invalid': invalid('category'),
  }, options);
  const limit = el('input', {
    type: 'text', inputmode: 'decimal', autocomplete: 'off', class: 'dash-input dash-b-input', id: 'dash-b-limit',
    'data-dash-focus': 'budget-limit', 'aria-describedby': 'dash-b-limit-err dash-b-limit-hint', 'aria-invalid': invalid('limit'),
    value: f.limit,
  });
  select.addEventListener('change', () => { f.category = select.value; });
  limit.addEventListener('input', () => { f.limit = limit.value; });

  const save = el('button', { type: 'submit', class: 'dash-btn dash-btn-accent', disabled: budgetUi.busy ? '' : null, 'data-dash-focus': 'budget-save' }, budgetUi.busy ? 'Saving…' : 'Save budget');
  const cancel = el('button', { type: 'button', class: 'dash-btn', disabled: budgetUi.busy ? '' : null, 'data-dash-focus': 'budget-cancel' }, 'Cancel');
  const close = () => {
    if (budgetUi.busy) return;
    const back = editing ? `budget-edit-${f.editingId}` : 'budget-add';
    budgetUi.form = null;
    redraw(back);
  };
  cancel.addEventListener('click', close);

  const scopeNote = scope.mode === 'group'
    ? `This budget is for the group ${ctx.groupName ? `“${ctx.groupName}”` : ''}. Every member sees it, and it counts what all members spend.`
    : 'This budget is just for you and counts only your own expenses.';
  const form = el('form', { class: 'dash-bform', novalidate: '', 'aria-labelledby': 'dash-b-title' }, [
    el('h4', { id: 'dash-b-title', class: 'dash-bform-title' }, editing ? 'Edit budget' : 'Add budget'),
    el('p', { class: 'dash-note' }, scopeNote),
    el('div', { class: 'dash-bfield' }, [
      el('label', { for: 'dash-b-category' }, 'Category'), select,
      el('span', { class: 'dash-b-error', id: 'dash-b-category-err' }, f.errors.category || ''),
    ]),
    el('div', { class: 'dash-bfield' }, [
      el('label', { for: 'dash-b-limit' }, 'Monthly limit (pesos)'), limit,
      el('span', { class: 'dash-bhint', id: 'dash-b-limit-hint' }, 'For example 5,000 or 1,234.50.'),
      el('span', { class: 'dash-b-error', id: 'dash-b-limit-err' }, f.errors.limit || ''),
    ]),
    f.formError ? el('p', { class: 'dash-b-error', role: 'alert' }, f.formError) : null,
    el('div', { class: 'dash-budget-actions' }, [save, cancel]),
  ].filter(Boolean));
  form.addEventListener('keydown', (e) => { if (e.key === 'Escape') close(); });
  form.addEventListener('submit', async (e) => {
    e.preventDefault();
    if (budgetUi.busy) return;
    f.errors = {};
    f.formError = '';
    const latest = live.ctx;
    const { errors, budget } = checkBudget(
      { me: latest.meId, groupId: scope.groupId, category: f.category, limit: f.limit, editingId: f.editingId },
      latest.budgets, latest.myGroupIds,
    );
    if (!budget) {
      f.errors = errors;
      f.formError = errors.scope || 'Some fields need fixing. Nothing was saved.';
      redraw(errors.category ? 'budget-category' : errors.limit ? 'budget-limit' : 'budget-save');
      return;
    }
    budgetUi.busy = true;
    save.disabled = true;
    cancel.disabled = true;
    const res = await runWrite(() => latest.onSaveBudget({ id: f.editingId, ...budget }));
    budgetUi.busy = false;
    if (res.ok) {
      budgetUi.form = null;
      budgetUi.status = `Saved the ${budget.category === null ? 'Overall' : categoryLabel(budget.category)} budget.`;
      afterWrite(res, editing ? `budget-edit-${f.editingId}` : 'budget-add');
    } else {
      f.formError = `${res.message} Your input is still here: fix the problem and save again.`;
      afterWrite(res, 'budget-limit');
    }
  });
  return form;
}

// Forgets a half-finished form or confirmation when the scope changed or the budget it was about is gone.
function syncBudgetUi(ctx, scope) {
  const key = `${scope.mode}:${scope.groupId}`;
  if (budgetUi.scopeKey !== key) budgetUi = freshBudgetUi(key);
  const mineNow = (id) => (ctx.budgets || []).some((b) => b && b.id === id && b.owner_id === ctx.meId && b.deleted !== true);
  if (budgetUi.form && budgetUi.form.editingId !== null && !mineNow(budgetUi.form.editingId)) budgetUi.form = null;
  if (budgetUi.confirmingId !== null && !mineNow(budgetUi.confirmingId)) budgetUi.confirmingId = null;
}

function buildBudgets(ctx, view, period, today) {
  const scope = { mode: view.group ? 'group' : 'personal', groupId: view.group ? (ctx.groupId || null) : null };
  const when = budgetMonth(period, today);
  const intro = view.group
    ? `Group budgets for ${ctx.groupName || 'this group'} count what every member spent in ${when.label}.`
    : `Your personal budgets count only your own expenses in ${when.label}.`;
  const section = card('Budgets vs actual', `${intro} Limits are monthly and only expenses count.`);
  if (when.note) section.append(el('p', { class: 'dash-note' }, when.note));

  if (ctx.budgetsError) {
    section.append(el('p', { class: 'dash-b-error', role: 'alert' }, `Budgets could not be loaded: ${ctx.budgetsError}`));
    if (typeof ctx.onRetryBudgets === 'function') {
      const retry = el('button', { type: 'button', class: 'dash-btn' }, 'Try again');
      retry.addEventListener('click', () => { retry.disabled = true; retry.textContent = 'Loading…'; ctx.onRetryBudgets(); });
      section.append(retry);
    }
    return section;
  }

  const canEdit = typeof ctx.onSaveBudget === 'function' && typeof ctx.onDeleteBudget === 'function';
  const usages = usageList(visibleBudgets(ctx.budgets, scope, ctx.meId), view.txns, when.month, view.members.map((m) => m.id));
  if (usages.length === 0) {
    section.append(plainNote(view.group ? 'This group has no budgets yet.' : 'You have no personal budgets yet.'));
  } else {
    section.append(el('ul', { class: 'dash-budgets', 'aria-label': `Budgets for ${when.label}` }, usages.map((u) => budgetRow(ctx, u, canEdit))));
  }
  if (budgetUi.status) section.append(el('p', { class: 'dash-note', role: 'status' }, budgetUi.status));

  if (canEdit) {
    if (budgetUi.form) {
      section.append(budgetForm(ctx, scope));
    } else {
      const add = el('button', { type: 'button', class: 'dash-btn dash-btn-accent', 'data-dash-focus': 'budget-add' }, 'Add budget');
      add.addEventListener('click', () => {
        if (budgetUi.busy) return;
        budgetUi.status = '';
        budgetUi.confirmingId = null;
        budgetUi.form = { editingId: null, category: '', limit: '', errors: {}, formError: '' };
        redraw('budget-category');
      });
      section.append(add);
    }
  }
  return section;
}

// --- savings goals (RFC-011) ----------------------------------------------------------------------------

function goalRow(p, walletName) {
  const li = el('li', { class: `dash-goal dash-goal-${p.reached ? 'done' : 'going'}` }, [
    el('div', { class: 'dash-goal-head' }, [
      el('span', { class: 'dash-goal-name' }, p.goal.name),
      el('span', { class: 'dash-goal-status' }, goalStatus(p)),
    ]),
    progressBar({ fraction: p.barFraction, tone: p.reached ? 'done' : 'ok', warnAt: null }),
    el('p', { class: 'dash-goal-text' }, goalSummary(p)),
  ]);
  const plan = goalPlanText(p);
  const deadline = goalDeadlineText(p.goal);
  if (plan) li.append(el('p', { class: 'dash-goal-line' }, plan));
  if (deadline) li.append(el('p', { class: 'dash-goal-line' }, deadline));
  if (walletName) li.append(el('p', { class: 'dash-goal-wallet' }, `Savings wallet: ${walletName}`));
  return li;
}

// Goals are all-time progress (the savings wallet's balance now), so the period picker does not change them. Read-only:
// they are made, edited and deleted on the phone. In group view each member's goals sit under their name.
function buildGoals(ctx, view, today) {
  const section = card('Savings goals', 'Progress is the balance of each goal’s savings wallet. Goals are set on the phone, so this list is read-only.');
  if (ctx.goalsError) {
    section.append(el('p', { class: 'dash-b-error', role: 'alert' }, `Goals could not be loaded: ${ctx.goalsError}`));
    if (typeof ctx.onRetryGoals === 'function') {
      const retry = el('button', { type: 'button', class: 'dash-btn' }, 'Try again');
      retry.addEventListener('click', () => { retry.disabled = true; retry.textContent = 'Loading…'; ctx.onRetryGoals(); });
      section.append(retry);
    }
    return section;
  }
  const owners = new Set(view.members.map((m) => m.id));
  if (!view.group && ctx.meId) owners.add(ctx.meId);
  const goals = visibleGoals(ctx.goals, view.group ? owners : new Set(ctx.meId ? [ctx.meId] : []));
  if (goals.length === 0) {
    section.append(plainNote(view.group
      ? 'No member has a savings goal yet.'
      : 'You have no savings goals yet. Add one on your phone: More, then Goals.'));
    return section;
  }
  const names = new Map(view.wallets.map((w) => [w.id, w.name]));
  const progress = goalsProgress(goals, view.wallets, view.allTxns, today);
  const listFor = (rows) => el('ul', { class: 'dash-goals', 'aria-label': 'Savings goals' },
    rows.map((p) => goalRow(p, names.get(p.goal.wallet_id) || null)));
  if (!view.group) {
    section.append(listFor(progress));
    return section;
  }
  for (const m of view.members) {
    const mine = progress.filter((p) => p.goal.owner_id === m.id);
    if (mine.length === 0) continue;
    section.append(
      el('h4', { class: 'dash-goal-owner' }, [m.name, m.id === ctx.meId ? '' : el('span', { class: 'dash-goal-ro' }, 'read-only')]),
      listFor(mine),
    );
  }
  return section;
}

// --- entry point -----------------------------------------------------------------------------------

export function renderDashboard(root, ctx) {
  const today = ctx.today || todayString();
  const view = scopeOf(ctx);
  live.root = root;
  live.ctx = ctx;
  syncBudgetUi(ctx, { mode: view.group ? 'group' : 'personal', groupId: view.group ? (ctx.groupId || null) : null });
  const rerender = (focusKey) => {
    renderDashboard(root, ctx);
    const target = root.querySelector(`[data-dash-focus="${focusKey}"]:not([disabled])`) || root.querySelector('[data-dash-focus="select"]');
    if (target) target.focus();
  };

  const period = selectedPeriod(today);
  const options = periodOptions(view.txns, period.kind, today);
  if (!options.some((o) => periodKey(o) === periodKey(period))) {
    options.push(period);
    options.sort((a, b) => (periodKey(a) < periodKey(b) ? 1 : -1));
  }
  const label = periodLabel(period);

  const dash = el('div', { class: 'dash' });
  root.replaceChildren(dash);

  const title = view.group ? (ctx.groupName ? `${ctx.groupName}: group spending` : 'Group spending') : 'Your spending';
  dash.append(el('header', { class: 'dash-head' }, [
    el('h2', { class: 'dash-title' }, title),
    el('p', { class: 'dash-sub' }, view.group
      ? `Everything ${view.members.length} ${view.members.length === 1 ? 'member has' : 'members have'} logged, income included.`
      : 'Only the entries you logged.'),
  ]));

  if (view.group && view.members.length === 0) {
    dash.append(el('section', { class: 'dash-empty', role: 'status' }, [
      el('h3', {}, 'No group to show'),
      el('p', {}, 'Create or join a group on your phone, then come back to compare members here.'),
    ]));
    return;
  }

  dash.append(buildControls(period, options, view, today, rerender));

  const rows = inPeriod(view.txns, period);
  const summary = summarize(rows);

  if (summary.entries === 0) {
    dash.append(buildEmpty(view, period, today, rerender), buildBudgets(ctx, view, period, today), buildGoals(ctx, view, today), buildWallets(view));
  } else {
    const ids = assignSeries(view.members);
    const highlight = period.kind === 'month' ? period.month - 1 : -1;
    dash.append(buildTiles(summary, label), buildBudgets(ctx, view, period, today), buildGoals(ctx, view, today), buildMonthly(view, period));
    const pair = el('div', { class: 'dash-pair' });
    pair.append(buildCategories(rows, summary, label));
    pair.append(view.group ? buildMembers(view, rows, label, ids) : buildWallets(view));
    dash.append(pair);
    if (view.group) dash.append(buildStacked(view, period.year, highlight, ids), buildWallets(view));
  }

  dash.append(el('p', { class: 'dash-foot' },
    'Transfers between wallets are not counted as spending or income. VAT is the amount printed on receipts: information only, not tax advice.'));
}
