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
//           today?: 'YYYY-MM-DD' }   // only for tests; the real date is used when it is missing
//
// All text from the data (names, categories) goes in through textContent, never innerHTML.
import { formatPesos } from './money.js';
import {
  MONTH_NAMES, MONTH_SHORT, summarize, inPeriod, monthlySpend, spendByCategory, memberStats,
  monthlyByMember, walletBalances, periodsAvailable, periodOptions, periodKey, periodLabel,
} from './stats.js';
import {
  el, columnChart, stackedColumnChart, barList, legend, table, dataTable, percentText,
} from './charts.js';

// The picker survives re-renders. year/month stay null until the person chooses, which means "the
// current month". `month` is remembered while in year mode so switching back returns to it.
let picker = { kind: 'month', year: null, month: null };

// Forget the chosen period (back to "this month"). RFC-007 can call this on sign-out so the next
// person does not inherit the last picker; the tests call it between checks.
export function resetDashboardPeriod() {
  picker = { kind: 'month', year: null, month: null };
}

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

// --- entry point -----------------------------------------------------------------------------------

export function renderDashboard(root, ctx) {
  const today = ctx.today || todayString();
  const view = scopeOf(ctx);
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
    dash.append(buildEmpty(view, period, today, rerender), buildWallets(view));
  } else {
    const ids = assignSeries(view.members);
    const highlight = period.kind === 'month' ? period.month - 1 : -1;
    dash.append(buildTiles(summary, label), buildMonthly(view, period));
    const pair = el('div', { class: 'dash-pair' });
    pair.append(buildCategories(rows, summary, label));
    pair.append(view.group ? buildMembers(view, rows, label, ids) : buildWallets(view));
    dash.append(pair);
    if (view.group) dash.append(buildStacked(view, period.year, highlight, ids), buildWallets(view));
  }

  dash.append(el('p', { class: 'dash-foot' },
    'Transfers between wallets are not counted as spending or income. VAT is the amount printed on receipts: information only, not tax advice.'));
}
