// Chimu web dashboard core (RFC-007): sign in, Personal / Group switch, Transactions tab (filters, table,
// totals, edit panel with the receipt photo, delete, CSV export) and the hook for the Dashboard tab.
// Vanilla ES modules. All text from the database goes in through textContent / setAttribute, never innerHTML.
import * as config from './config.js';
import { makeClient } from './supa.js';
import { formatPesos, pesosPlain } from './money.js';
import { csvTotals, buildCsv } from './csv.js';
import { ALL_CATEGORIES, categoryLabel, categoriesFor } from './categories.js';
import { EMPTY_FILTERS, NO_CATEGORY, applyFilters, sortTxns } from './filters.js';
import { validateEdit, nextUpdatedAt, LIMITS } from './edit.js';
import {
  loadDirectory, loadScope, loadBudgets, loadGoals, updateOwnTxn, tombstoneTxn, createBudget, updateOwnBudget, tombstoneBudget,
  signedPhotoUrl,
} from './data.js';

const ROWS_PER_PAGE = 200;
const TYPE_LABEL = { expense: 'Expense', income: 'Income', transfer: 'Transfer' };
const NO_DIRECTORY = () => ({ groups: [], memberIds: () => [], displayName: new Map() });

const state = {
  client: null,
  me: null, // { id, email }
  directory: NO_DIRECTORY(),
  scope: { mode: 'personal', groupId: null },
  lastGroupId: null, // the group the picker shows, kept when switching to Personal and back
  groupName: null,
  members: [], // [{ id, name }], me first
  wallets: [], // every wallet of the scope's owners, archived ones flagged deleted
  txns: [], // ALL non-deleted transactions of the scope, unfiltered (RFC-007 contract)
  budgets: [], // live budgets of the scope (RFC-009): mine with no group in Personal, the group's in Group
  budgetsError: '', // why the budgets did not load; shown in the Budgets card, never mistaken for "no budgets"
  goals: [], // live goals of the scope's members (RFC-011): mine in Personal, every member's in Group. Read-only here
  goalsError: '', // why the goals did not load; shown in the Goals card, never mistaken for "no goals"
  filters: { ...EMPTY_FILTERS },
  filtered: [],
  visible: ROWS_PER_PAGE,
  selectedId: null,
  loadToken: 0,
  loading: false,
  dashboard: null, // the imported dashboard.js module, loaded on first use
};

const panel = { dirty: false, saving: false, afterDiscard: null, photoToken: 0, gone: false };

// ---------------------------------------------------------------- small helpers
const $ = (id) => document.getElementById(id);

// h('div', { class: 'x', 'aria-label': 'y' }, 'text', childNode, [more]) -> element. Text is always a text node.
function h(tag, attrs = {}, ...kids) {
  const el = document.createElement(tag);
  for (const [k, v] of Object.entries(attrs || {})) {
    if (v === false || v === null || v === undefined) continue;
    if (k === 'class') el.className = v;
    else el.setAttribute(k, v === true ? '' : String(v));
  }
  for (const kid of kids.flat()) {
    if (kid === null || kid === undefined || kid === false) continue;
    el.append(kid instanceof Node ? kid : document.createTextNode(String(kid)));
  }
  return el;
}

function localToday() {
  const d = new Date();
  const p = (n) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`;
}

function configured() {
  const url = String(config.SUPABASE_URL || '');
  const key = String(config.SUPABASE_ANON_KEY || '');
  return /^https?:\/\//.test(url) && !/YOUR-/i.test(url) && key.length > 20 && !/YOUR-/i.test(key);
}

function errText(e) {
  return (e && e.message) ? e.message : String(e);
}

// ---------------------------------------------------------------- banner (visible errors, RULES 20)
let bannerRetry = null;
function showBanner(kind, text, retry = null) {
  const box = $('banner');
  box.className = kind === 'error' ? 'banner error' : 'banner';
  $('banner-text').textContent = text;
  $('banner-text').setAttribute('role', kind === 'error' ? 'alert' : 'status');
  bannerRetry = retry;
  $('banner-retry').hidden = !retry;
  box.hidden = false;
}
function hideBanner() {
  $('banner').hidden = true;
  bannerRetry = null;
}

// ---------------------------------------------------------------- views
function showView(name) {
  for (const id of ['view-setup', 'view-login', 'view-app']) $(id).hidden = id !== `view-${name}`;
  $('topbar').hidden = name !== 'app';
}

function showLogin(message = '') {
  showView('login');
  const err = $('login-error');
  err.textContent = message;
  err.hidden = !message;
  $('login-submit').disabled = false;
  $('login-email').focus();
}

function resetSession() {
  state.me = null;
  state.directory = NO_DIRECTORY();
  state.scope = { mode: 'personal', groupId: null };
  state.lastGroupId = null;
  state.groupName = null;
  state.members = [];
  state.wallets = [];
  state.txns = [];
  state.budgets = [];
  state.budgetsError = '';
  state.goals = [];
  state.goalsError = '';
  state.filtered = [];
  state.filters = { ...EMPTY_FILTERS };
  state.selectedId = null;
  state.loadToken += 1; // any load still in flight is now stale
  closePanelNow();
  $('tab-dashboard').replaceChildren();
  if (state.dashboard) state.dashboard.resetDashboardPeriod?.(); // the next person starts at this month
  $('txn-body').replaceChildren();
  $('txn-foot').replaceChildren();
  hideBanner();
}

// ---------------------------------------------------------------- sign in / out
async function onLoginSubmit(ev) {
  ev.preventDefault();
  const email = $('login-email').value.trim();
  const password = $('login-password').value;
  if (!email || !password) { showLogin('Enter your email and password.'); return; }
  $('login-submit').disabled = true;
  $('login-error').hidden = true;
  try {
    const { data, error } = await state.client.auth.signInWithPassword({ email, password });
    if (error) throw error;
    $('login-password').value = '';
    await enter(data.user || (data.session && data.session.user));
  } catch (e) {
    showLogin(errText(e) || 'Could not sign in.');
  }
}

async function enter(user) {
  if (!user) { showLogin('Could not read your account. Try again.'); return; }
  resetSession();
  state.me = { id: user.id, email: user.email || '' };
  $('who-email').textContent = state.me.email;
  showView('app');
  showTab('transactions');
  syncScopeControls();
  renderFilterOptions();
  renderTable();
  await loadData({ scope: { mode: 'personal', groupId: null }, reloadDirectory: true });
  $('main').focus();
}

async function signOut() {
  try {
    await state.client.auth.signOut();
  } catch (e) {
    // The session is cleared on this device even if the server could not be told.
    console.warn('sign out:', errText(e));
  }
  if (state.me) { resetSession(); showLogin(''); }
}

// ---------------------------------------------------------------- loading data
function setBusy(on) {
  state.loading = on;
  $('refresh').disabled = on;
  $('scope-personal').disabled = on;
  $('scope-group').disabled = on || state.directory.groups.length === 0;
  $('group-pick').disabled = on;
  $('txn-table').parentElement.setAttribute('aria-busy', on ? 'true' : 'false');
  if (on && state.txns.length === 0) {
    const e = $('empty');
    e.textContent = 'Loading…';
    e.hidden = false;
  }
}

// Loads the whole scope (personal or one group) once. On failure the screen keeps what it had and says why.
async function loadData({ scope = state.scope, reloadDirectory = false } = {}) {
  const token = ++state.loadToken;
  setBusy(true);
  try {
    if (reloadDirectory) state.directory = await loadDirectory(state.client);
    let target = scope;
    let note = '';
    if (target.mode === 'group' && !state.directory.groups.some((g) => g.id === target.groupId)) {
      target = { mode: 'personal', groupId: null };
      note = 'That group is no longer available, so you are back on your personal view.';
    }
    const data = await loadScope(state.client, state.directory, state.me, target);
    // Budgets have their own failure: the transactions still show, and the Budgets card says what went wrong.
    let budgets = null;
    let budgetsError = '';
    try {
      budgets = await loadBudgets(state.client, state.me, target);
    } catch (e) {
      budgetsError = errText(e);
    }
    // Goals too: their own failure, so the rest still shows and the Goals card says what went wrong.
    let goals = null;
    let goalsError = '';
    try {
      goals = await loadGoals(state.client, data.members.map((m) => m.id));
    } catch (e) {
      goalsError = errText(e);
    }
    if (token !== state.loadToken) return false; // a newer load or a sign-out replaced this one
    const sameScope = state.scope.mode === target.mode && state.scope.groupId === target.groupId;
    state.scope = target;
    if (target.groupId) state.lastGroupId = target.groupId;
    const g = state.directory.groups.find((x) => x.id === target.groupId);
    state.groupName = target.mode === 'group' && g ? g.name : null;
    state.members = data.members;
    state.wallets = data.wallets;
    state.txns = data.txns;
    state.budgets = budgets || (sameScope ? state.budgets : []); // a failed reload keeps the list on screen
    state.budgetsError = budgetsError;
    state.goals = goals || (sameScope ? state.goals : []); // a failed reload keeps the list on screen
    state.goalsError = goalsError;
    state.visible = ROWS_PER_PAGE;
    if (note) showBanner('info', note); else hideBanner();
    syncScopeControls();
    renderFilterOptions();
    renderTable();
    paintDashboard();
    refreshOpenPanel();
    return true;
  } catch (e) {
    if (token !== state.loadToken) return false;
    showBanner('error', `Could not load your transactions: ${errText(e)}. What is on screen has not changed.`,
      () => loadData({ scope, reloadDirectory }));
    syncScopeControls();
    return false;
  } finally {
    if (token === state.loadToken) setBusy(false);
  }
}

function switchScope(next) {
  withPanelClosed(async () => {
    const ok = await loadData({ scope: next });
    if (!ok) syncScopeControls(); // put the buttons back to the scope that is really on screen
  });
}

function syncScopeControls() {
  const groups = state.directory.groups;
  const inGroup = state.scope.mode === 'group';
  $('scope-personal').setAttribute('aria-pressed', String(!inGroup));
  $('scope-group').setAttribute('aria-pressed', String(inGroup));
  const groupBtn = $('scope-group');
  groupBtn.disabled = state.loading || groups.length === 0;
  groupBtn.title = groups.length === 0 ? 'You are not in a group yet. Create or join one in the phone app.' : '';
  const sel = $('group-pick');
  const wanted = state.scope.groupId || state.lastGroupId || (groups[0] && groups[0].id) || '';
  sel.replaceChildren(...groups.map((g) => h('option', { value: g.id }, g.name)));
  sel.value = wanted;
  $('group-pick-wrap').hidden = !(inGroup && groups.length > 1);
  $('f-member-wrap').hidden = !inGroup;
  $('scope-title').textContent = inGroup
    ? `Group "${state.groupName || ''}": ${state.members.length} member${state.members.length === 1 ? '' : 's'}. You can edit only your own rows.`
    : 'Your own transactions.';
}

// ---------------------------------------------------------------- names
function memberNames() { return new Map(state.members.map((m) => [m.id, m.name])); }
function walletNames() { return new Map(state.wallets.map((w) => [w.id, w.name])); }

function walletLabel(w) {
  const bits = [];
  if (state.scope.mode === 'group') bits.push(memberNames().get(w.owner_id) || 'Unknown');
  if (w.deleted) bits.push('archived');
  return bits.length ? `${w.name} (${bits.join(', ')})` : w.name;
}

function walletText(t) {
  const names = walletNames();
  const from = names.get(t.wallet_id) || 'Unknown wallet';
  return t.type === 'transfer' ? `${from} to ${names.get(t.to_wallet_id) || 'Unknown wallet'}` : from;
}

// ---------------------------------------------------------------- filters
function renderFilterOptions() {
  const keep = { ...state.filters };
  const fill = (id, options, current) => {
    const sel = $(id);
    sel.replaceChildren(...options.map(([value, text]) => h('option', { value }, text)));
    sel.value = options.some(([value]) => value === current) ? current : '';
    return sel.value;
  };
  keep.member = fill('f-member', [['', 'All members'], ...state.members.map((m) => [m.id, m.name])], keep.member);
  keep.wallet = fill('f-wallet', [['', 'All wallets'], ...state.wallets.map((w) => [w.id, walletLabel(w)])], keep.wallet);
  keep.category = fill('f-category', [['', 'All categories'], [NO_CATEGORY, 'No category'],
    ...ALL_CATEGORIES.map((c) => [c.slug, c.label])], keep.category);
  state.filters = keep;
}

function readFilters() {
  state.filters = {
    from: $('f-from').value,
    to: $('f-to').value,
    member: $('f-member').value,
    wallet: $('f-wallet').value,
    category: $('f-category').value,
    type: $('f-type').value,
    business: $('f-business').checked,
    search: $('f-search').value,
  };
  state.visible = ROWS_PER_PAGE;
  renderTable();
}

function clearFilters() {
  $('f-from').value = '';
  $('f-to').value = '';
  $('f-type').value = '';
  $('f-business').checked = false;
  $('f-search').value = '';
  $('f-member').value = '';
  $('f-wallet').value = '';
  $('f-category').value = '';
  readFilters();
}

// ---------------------------------------------------------------- table
function renderTable() {
  const inGroup = state.scope.mode === 'group';
  const names = { members: memberNames(), wallets: walletNames() };
  state.filtered = sortTxns(applyFilters(state.txns, state.filters, names));

  const cols = ['Date', ...(inGroup ? ['Member'] : []), 'Type', 'Wallet', 'Category', 'Merchant', 'Amount', 'Notes'];
  $('txn-head').replaceChildren(...cols.map((c) => h('th', { scope: 'col', class: c === 'Amount' ? 'amount' : '' }, c)));

  const shown = state.filtered.slice(0, state.visible);
  $('txn-body').replaceChildren(...shown.map((t) => rowFor(t, inGroup)));

  const totals = csvTotals(state.filtered);
  const n = state.filtered.length;
  $('txn-foot').replaceChildren(h('tr', {},
    h('td', { colspan: cols.length, class: 'totals-label' },
      `Totals for ${n} transaction${n === 1 ? '' : 's'}: `,
      h('strong', {}, `Spent ${formatPesos(totals.spent)}`), '  |  ',
      h('strong', {}, `Income ${formatPesos(totals.income)}`), '  |  ',
      h('strong', {}, `Net ${formatPesos(totals.net)}`), '  |  ',
      h('strong', {}, `VAT on expenses ${formatPesos(totals.vat)}`))));

  const empty = $('empty');
  if (state.loading && state.txns.length === 0) {
    empty.textContent = 'Loading…';
    empty.hidden = false;
  } else if (n === 0) {
    empty.textContent = state.txns.length === 0 ? 'No transactions yet.' : 'No transactions match these filters.';
    empty.hidden = false;
  } else {
    empty.hidden = true;
  }

  const more = $('show-more');
  const left = n - shown.length;
  more.hidden = left <= 0;
  more.textContent = `Show ${Math.min(ROWS_PER_PAGE, left)} more (${left} not shown)`;

  const hint = $('filter-hint');
  const bad = state.filters.from && state.filters.to && state.filters.from > state.filters.to;
  hint.textContent = bad ? 'The From date is after the To date, so nothing can match.' : '';
  hint.hidden = !bad;
}

function signedAmount(t) {
  const money = formatPesos(t.amount);
  if (t.type === 'expense') return `-${money}`;
  if (t.type === 'income') return `+${money}`;
  return money;
}

function rowFor(t, inGroup) {
  const names = memberNames();
  const merchant = t.merchant || '';
  const notes = [
    t.is_business ? h('span', { class: 'tag' }, 'Business') : null,
    t.receipt_path ? h('span', { class: 'tag' }, 'Photo') : null,
    t.owner_id !== state.me.id ? h('span', { class: 'tag' }, 'Read-only') : null,
  ];
  const label = `Open ${TYPE_LABEL[t.type] || t.type} on ${t.date}${merchant ? ` at ${merchant}` : ''}`;
  return h('tr', { 'data-id': t.id, class: t.id === state.selectedId ? 'selected' : '' },
    h('td', {}, h('button', { type: 'button', class: 'open', 'aria-label': label }, t.date)),
    inGroup ? h('td', {}, names.get(t.owner_id) || 'Unknown') : null,
    h('td', {}, TYPE_LABEL[t.type] || t.type),
    h('td', {}, walletText(t)),
    h('td', {}, categoryLabel(t.category)),
    h('td', { class: 'merchant', title: merchant }, merchant),
    h('td', { class: 'amount' }, signedAmount(t)),
    h('td', {}, notes));
}

// ---------------------------------------------------------------- CSV export
function triggerDownload(filename, blob) {
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = filename;
  a.rel = 'noopener';
  document.body.append(a);
  a.click();
  a.remove();
  setTimeout(() => URL.revokeObjectURL(url), 10000);
}

function exportCsv() {
  const rows = state.filtered;
  if (rows.length === 0) { showBanner('info', 'Nothing to export: no transactions match the current filters.'); return; }
  const text = buildCsv(rows, { members: state.members, wallets: state.wallets });
  const name = `chimu-${localToday()}.csv`;
  triggerDownload(name, new Blob([text], { type: 'text/csv;charset=utf-8' }));
  showBanner('info', `Exported ${rows.length} transaction${rows.length === 1 ? '' : 's'} to ${name} (the current filters, totals included).`);
}

// ---------------------------------------------------------------- tabs and the Dashboard hook
function showTab(name) {
  for (const t of ['transactions', 'dashboard']) {
    const on = t === name;
    $(`tab-${t}`).hidden = !on;
    const btn = $(`tab-btn-${t}`);
    btn.setAttribute('aria-selected', String(on));
    btn.tabIndex = on ? 0 : -1;
  }
  if (name === 'dashboard') paintDashboard();
}

function buildCtx() {
  return {
    mode: state.scope.mode,
    meId: state.me.id,
    groupName: state.scope.mode === 'group' ? state.groupName : null,
    members: state.members.map((m) => ({ id: m.id, name: m.name })),
    wallets: state.wallets.filter((w) => !w.deleted).map((w) => ({
      id: w.id, owner_id: w.owner_id, name: w.name, type: w.type, opening_balance: w.opening_balance, deleted: false,
    })),
    txns: state.txns,
    groupId: state.scope.mode === 'group' ? state.scope.groupId : null,
    myGroupIds: state.directory.groups.map((g) => g.id),
    budgets: state.budgets,
    budgetsError: state.budgetsError,
    onSaveBudget: saveBudget,
    onDeleteBudget: deleteBudget,
    onRetryBudgets: reloadBudgets,
    goals: state.goals,
    goalsError: state.goalsError,
    onRetryGoals: reloadGoals,
  };
}

// ---------------------------------------------------------------- budgets (RFC-009)
// The dashboard never talks to Supabase: it validates, then calls these, and redraws with the list they return.
function budgetInScope(row) {
  if (state.scope.mode === 'group') return row.group_id === state.scope.groupId;
  return row.group_id === null && row.owner_id === state.me.id;
}

// Swap one budget in memory with what the server returned. A tombstoned or out-of-scope row leaves the list.
function mergeBudget(row) {
  const rest = state.budgets.filter((b) => b.id !== row.id);
  state.budgets = !row.deleted && budgetInScope(row) ? [...rest, row] : rest;
}

// What the dashboard gets back: ok or not, the words for the person, and the list as it now stands.
function budgetAnswer(res) {
  if (res.ok || res.reason === 'discarded') mergeBudget(res.row); // a discarded write returns the newer stored row
  return { ok: res.ok, message: res.message, budgets: state.budgets };
}

async function saveBudget(change) {
  const mine = change.id ? state.budgets.find((b) => b.id === change.id && b.owner_id === state.me.id) : null;
  if (change.id && !mine) {
    return { ok: false, message: 'This budget is no longer in the list, so nothing was saved.', budgets: state.budgets };
  }
  let res;
  try {
    res = mine
      ? await updateOwnBudget(state.client, mine.id, state.me.id,
        { category: change.category, monthly_limit: change.monthlyLimit }, nextUpdatedAt(Date.now(), mine))
      : await createBudget(state.client, state.me.id, change, Date.now());
  } catch (e) {
    res = { ok: false, reason: 'error', message: errText(e) };
  }
  return budgetAnswer(res);
}

async function deleteBudget(id) {
  const mine = state.budgets.find((b) => b.id === id && b.owner_id === state.me.id);
  if (!mine) return { ok: false, message: 'This budget is no longer in the list.', budgets: state.budgets };
  let res;
  try {
    res = await tombstoneBudget(state.client, mine.id, state.me.id, nextUpdatedAt(Date.now(), mine));
  } catch (e) {
    res = { ok: false, reason: 'error', message: errText(e) };
  }
  return budgetAnswer(res);
}

// "Try again" in the Budgets card after a failed load.
async function reloadBudgets() {
  const token = state.loadToken;
  let error = '';
  let budgets = null;
  try {
    budgets = await loadBudgets(state.client, state.me, state.scope);
  } catch (e) {
    error = errText(e);
  }
  if (token !== state.loadToken || !state.me) return; // a newer load or a sign-out replaced this one
  if (budgets) state.budgets = budgets;
  state.budgetsError = error;
  paintDashboard();
}

// "Try again" in the Goals card after a failed load (RFC-011).
async function reloadGoals() {
  const token = state.loadToken;
  let error = '';
  let goals = null;
  try {
    goals = await loadGoals(state.client, state.members.map((m) => m.id));
  } catch (e) {
    error = errText(e);
  }
  if (token !== state.loadToken || !state.me) return; // a newer load or a sign-out replaced this one
  if (goals) state.goals = goals;
  state.goalsError = error;
  paintDashboard();
}

// The Dashboard tab (RFC-008) draws itself from ctx. It is imported on first use so a problem in it
// can never stop sign-in or the Transactions tab.
async function paintDashboard() {
  if (!state.me) return;
  const root = $('tab-dashboard');
  try {
    if (!state.dashboard) state.dashboard = await import('./dashboard.js');
    if (!state.me) return;
    state.dashboard.renderDashboard(root, buildCtx());
  } catch (e) {
    root.replaceChildren(h('p', { class: 'error' }, `The dashboard could not be drawn: ${errText(e)}`));
  }
}

// ---------------------------------------------------------------- edit panel
function findTxn(id) { return state.txns.find((t) => t.id === id) || null; }

function panelOpen() { return !$('panel').hidden; }

// Runs `cont` once the panel is closed. With unsaved input it asks first, in the page (RULES 20).
function withPanelClosed(cont) {
  if (!panelOpen()) { cont(); return; }
  if (panel.dirty) {
    panel.afterDiscard = () => { closePanelNow(); cont(); };
    $('panel-discard').hidden = false;
    $('discard-no').focus();
    return;
  }
  closePanelNow();
  cont();
}

function closePanelNow() {
  const id = state.selectedId;
  state.selectedId = null;
  panel.dirty = false;
  panel.afterDiscard = null;
  panel.photoToken += 1;
  $('panel').hidden = true;
  $('panel-discard').hidden = true;
  $('panel-form').replaceChildren();
  $('panel-photo').replaceChildren();
  let back = null;
  for (const tr of document.querySelectorAll('#txn-body tr')) {
    tr.classList.remove('selected');
    if (id && tr.dataset.id === id) back = tr.querySelector('button.open');
  }
  if (back) back.focus();
}

function openPanel(id) {
  if (panelOpen() && id === state.selectedId) return;
  if (panelOpen() && panel.dirty) {
    panel.afterDiscard = () => openPanelNow(id);
    $('panel-discard').hidden = false;
    $('discard-no').focus();
    return;
  }
  openPanelNow(id);
}

function openPanelNow(id) {
  const row = findTxn(id);
  if (!row) return;
  state.selectedId = id;
  for (const tr of document.querySelectorAll('#txn-body tr')) tr.classList.toggle('selected', tr.dataset.id === id);
  renderPanel(row);
  $('panel').hidden = false;
  $('panel-title').focus();
}

// Draws the panel for a row. Own rows get the form, others' rows a read-only list.
function renderPanel(row) {
  panel.dirty = false;
  panel.gone = false;
  $('panel-discard').hidden = true;
  const mine = row.owner_id === state.me.id;
  const ownerName = memberNames().get(row.owner_id) || 'another member';
  $('panel-title').textContent = mine ? 'Edit transaction' : `Transaction by ${ownerName} (read-only)`;
  renderPhoto(row);
  $('panel-form').replaceChildren(mine ? buildForm(row) : buildReadOnly(row, ownerName));
}

// After a reload: keep the panel in step with the data, but never overwrite what the user is typing.
function refreshOpenPanel() {
  if (!state.selectedId) return;
  const row = findTxn(state.selectedId);
  if (!row) {
    panel.gone = true;
    for (const b of document.querySelectorAll('#panel-form button[data-needs-row]')) b.disabled = true;
    const form = $('panel-form');
    if (!form.querySelector('.gone-note')) {
      form.prepend(h('p', { class: 'error gone-note', role: 'alert' },
        'This transaction is no longer in the list (deleted, or no longer visible to you). Close this panel to discard anything you typed.'));
    }
    return;
  }
  if (!panel.dirty) renderPanel(row);
}

async function renderPhoto(row) {
  const box = $('panel-photo');
  const token = ++panel.photoToken;
  if (!row.receipt_path) {
    box.replaceChildren(h('p', { class: 'muted' }, 'No photo attached.'));
    return;
  }
  box.replaceChildren(h('p', { class: 'muted' }, 'Loading photo…'));
  try {
    const url = await signedPhotoUrl(state.client, row.receipt_path);
    if (token !== panel.photoToken) return;
    const img = h('img', { src: url, alt: 'Receipt photo', referrerpolicy: 'no-referrer' });
    img.addEventListener('error', () => {
      if (token !== panel.photoToken) return;
      box.replaceChildren(h('p', { class: 'error', role: 'alert' }, 'The photo could not be shown.'), retryPhotoButton(row));
    });
    box.replaceChildren(img, h('p', { class: 'small' },
      h('a', { href: url, target: '_blank', rel: 'noopener noreferrer' }, 'Open full size in a new tab')));
  } catch (e) {
    if (token !== panel.photoToken) return;
    box.replaceChildren(h('p', { class: 'error', role: 'alert' }, `The photo could not be loaded: ${errText(e)}`), retryPhotoButton(row));
  }
}

function retryPhotoButton(row) {
  const b = h('button', { type: 'button' }, 'Try again');
  b.addEventListener('click', () => renderPhoto(row));
  return b;
}

function buildReadOnly(row, ownerName) {
  const fact = (k, v) => [h('dt', {}, k), h('dd', {}, v === null || v === undefined || v === '' ? '-' : v)];
  const money = (v) => (v === null || v === undefined ? null : formatPesos(v));
  return h('div', {},
    h('p', { class: 'notice' }, `This row belongs to ${ownerName}. You can look at it but not change it.`),
    h('dl', { class: 'facts' },
      fact('Logged by', ownerName),
      fact('Type', TYPE_LABEL[row.type] || row.type),
      fact('Date', row.date),
      fact('Amount', signedAmount(row)),
      fact('Wallet', walletText(row)),
      fact('Category', categoryLabel(row.category)),
      fact('Merchant', row.merchant),
      fact('Note', row.note),
      fact('Business', row.is_business ? 'Yes' : 'No'),
      fact('Source', row.source),
      fact('Seller TIN', row.seller_tin),
      fact('Invoice no.', row.invoice_no),
      fact('VATable sales', money(row.vatable_sales)),
      fact('VAT', money(row.vat)),
      fact('VAT-exempt sales', money(row.vat_exempt)),
      fact('Reference no.', row.ref_no)));
}

function buildForm(row) {
  const ownWallets = state.wallets.filter((w) => w.owner_id === state.me.id && !w.deleted);
  const walletOptions = (current) => {
    const list = [...ownWallets];
    const cur = state.wallets.find((w) => w.id === current);
    if (cur && cur.deleted) list.push(cur);
    return list.map((w) => h('option', { value: w.id }, w.deleted ? `${w.name} (archived)` : w.name));
  };

  const control = {};
  const errEls = {};
  const field = (name, label, el, extra = '') => {
    el.id = `pf-${name}`;
    control[name] = el;
    const err = h('span', { class: 'err', id: `err-${name}` });
    errEls[name] = err;
    el.setAttribute('aria-describedby', err.id);
    return h('div', { class: `field ${extra}`.trim(), id: `fld-${name}` }, h('label', { for: el.id }, label), el, err);
  };
  const text = (value, max) => h('input', { type: 'text', value: value ?? '', maxlength: max, autocomplete: 'off' });
  const money = (value) => h('input', {
    type: 'text', inputmode: 'decimal', autocomplete: 'off', value: value === null || value === undefined ? '' : pesosPlain(value),
  });

  const typeSel = h('select', {}, ...['expense', 'income', 'transfer'].map((t) => h('option', { value: t }, TYPE_LABEL[t])));
  const categorySel = h('select', {});
  const walletSel = h('select', {}, ...walletOptions(row.wallet_id));
  const toWalletSel = h('select', {}, h('option', { value: '' }, 'Choose a wallet'), ...walletOptions(row.to_wallet_id));
  const noteBox = h('textarea', { maxlength: LIMITS.note });
  noteBox.value = row.note || '';
  const business = h('input', { type: 'checkbox', id: 'pf-is_business' });
  business.checked = row.is_business === true;
  control.is_business = business;

  const fillCategories = (type) => {
    const opts = categoriesFor(type).map((c) => [c.slug, c.label]);
    if (type === row.type && row.category && !opts.some(([s]) => s === row.category)) opts.push([row.category, categoryLabel(row.category)]);
    categorySel.replaceChildren(h('option', { value: '' }, 'No category'), ...opts.map(([s, l]) => h('option', { value: s }, l)));
  };
  typeSel.value = row.type;
  fillCategories(row.type);
  categorySel.value = row.category || '';
  walletSel.value = row.wallet_id;
  toWalletSel.value = row.to_wallet_id || '';

  const dateInput = h('input', { type: 'date', value: row.date });
  const amountInput = money(row.amount);

  const status = h('p', { id: 'pf-status', role: 'status', class: 'muted' });
  const formError = h('p', { id: 'pf-error', class: 'form-error error', role: 'alert', hidden: true });
  const saveBtn = h('button', { type: 'submit', class: 'primary', 'data-needs-row': '' }, 'Save changes');
  const deleteBtn = h('button', { type: 'button', class: 'danger', 'data-needs-row': '' }, 'Delete…');
  const confirmBox = h('div', { class: 'notice danger form-error', id: 'pf-confirm', hidden: true },
    h('p', {}, 'Delete this transaction? It will leave the totals here and on the phone after its next sync.'));
  const confirmYes = h('button', { type: 'button', class: 'danger', id: 'pf-confirm-yes' }, 'Yes, delete it');
  const confirmNo = h('button', { type: 'button', id: 'pf-confirm-no' }, 'Cancel');
  confirmBox.append(confirmYes, confirmNo);

  const form = h('form', { id: 'edit-form', novalidate: true },
    field('type', 'Type', typeSel),
    field('date', 'Date', dateInput),
    field('amount', 'Amount (pesos)', amountInput),
    field('wallet_id', 'Wallet', walletSel),
    field('to_wallet_id', 'Moves money to', toWalletSel),
    field('category', 'Category', categorySel),
    field('merchant', 'Merchant', text(row.merchant, LIMITS.merchant), 'wide'),
    field('note', 'Note', noteBox, 'wide'),
    h('div', { class: 'field wide check' }, business, h('label', { for: 'pf-is_business' }, 'Business'),
      h('span', { class: 'muted small' }, 'Tags this row for the business-only filter. What counts for tax is for your accountant to decide.')),
    h('fieldset', {}, h('legend', {}, 'Receipt and VAT details (optional)'),
      field('seller_tin', 'Seller TIN', text(row.seller_tin, LIMITS.seller_tin)),
      field('invoice_no', 'Invoice no.', text(row.invoice_no, LIMITS.invoice_no)),
      field('ref_no', 'Reference no.', text(row.ref_no, LIMITS.ref_no)),
      field('vatable_sales', 'VATable sales', money(row.vatable_sales)),
      field('vat', 'VAT amount', money(row.vat)),
      field('vat_exempt', 'VAT-exempt sales', money(row.vat_exempt))),
    h('p', { class: 'muted small wide' }, `Source: ${row.source || 'manual'}.`),
    formError,
    confirmBox,
    h('div', { class: 'actions' }, saveBtn, deleteBtn, status));

  // A transfer needs a second wallet and has no category; the other types are the reverse.
  const toggleFields = () => {
    const t = typeSel.value;
    form.querySelector('#fld-to_wallet_id').hidden = t !== 'transfer';
    form.querySelector('#fld-category').hidden = t === 'transfer';
  };
  toggleFields();
  typeSel.addEventListener('change', () => {
    fillCategories(typeSel.value);
    categorySel.value = '';
    toggleFields();
  });

  const values = () => ({
    type: typeSel.value,
    date: dateInput.value,
    amount: amountInput.value,
    wallet_id: walletSel.value,
    to_wallet_id: toWalletSel.value,
    category: categorySel.value,
    merchant: control.merchant.value,
    note: noteBox.value,
    is_business: business.checked,
    seller_tin: control.seller_tin.value,
    invoice_no: control.invoice_no.value,
    ref_no: control.ref_no.value,
    vatable_sales: control.vatable_sales.value,
    vat: control.vat.value,
    vat_exempt: control.vat_exempt.value,
  });

  const clearErrors = () => {
    for (const [name, el] of Object.entries(errEls)) {
      el.textContent = '';
      control[name].removeAttribute('aria-invalid');
    }
    formError.hidden = true;
    formError.textContent = '';
    status.textContent = '';
  };
  const failForm = (message) => {
    formError.textContent = message;
    formError.hidden = false;
  };
  const setBusyForm = (on) => {
    panel.saving = on;
    saveBtn.disabled = on;
    deleteBtn.disabled = on;
    confirmYes.disabled = on;
    confirmNo.disabled = on;
    saveBtn.textContent = on ? 'Saving…' : 'Save changes';
  };

  form.addEventListener('input', () => { panel.dirty = true; });
  form.addEventListener('change', () => { panel.dirty = true; });

  form.addEventListener('submit', async (ev) => {
    ev.preventDefault();
    if (panel.saving || panel.gone) return;
    clearErrors();
    confirmBox.hidden = true;
    const current = findTxn(row.id);
    if (!current) { failForm('This transaction is no longer in the list, so nothing was saved.'); return; }
    const { errors, payload } = validateEdit(values(), { ownWalletIds: ownWallets.map((w) => w.id), original: current });
    if (!payload) {
      let first = null;
      for (const [name, msg] of Object.entries(errors)) {
        errEls[name].textContent = msg;
        control[name].setAttribute('aria-invalid', 'true');
        first = first || control[name];
      }
      failForm('Some fields need fixing. Nothing was saved.');
      if (first) first.focus();
      return;
    }
    setBusyForm(true);
    let res;
    try {
      res = await updateOwnTxn(state.client, current.id, state.me.id, payload, nextUpdatedAt(Date.now(), current));
    } catch (e) {
      res = { ok: false, reason: 'error', message: errText(e) };
    }
    setBusyForm(false);
    if (res.ok) {
      replaceRow(res.row);
      renderPanel(res.row); // shows what the server stored
      $('pf-status').textContent = 'Saved.';
      return;
    }
    if (res.reason === 'discarded') replaceRow(res.row);
    failForm(`${res.message} Your input is still here: fix the problem and save again.`);
  });

  deleteBtn.addEventListener('click', () => {
    confirmBox.hidden = false;
    confirmNo.focus();
  });
  confirmNo.addEventListener('click', () => { confirmBox.hidden = true; deleteBtn.focus(); });
  confirmYes.addEventListener('click', async () => {
    if (panel.saving || panel.gone) return;
    clearErrors();
    const current = findTxn(row.id);
    if (!current) { confirmBox.hidden = true; failForm('This transaction is no longer in the list.'); return; }
    setBusyForm(true);
    let res;
    try {
      res = await tombstoneTxn(state.client, current.id, state.me.id, nextUpdatedAt(Date.now(), current));
    } catch (e) {
      res = { ok: false, reason: 'error', message: errText(e) };
    }
    setBusyForm(false);
    if (res.ok) {
      state.txns = state.txns.filter((t) => t.id !== current.id);
      closePanelNow();
      renderTable();
      paintDashboard();
      showBanner('info', 'Transaction deleted. It leaves the phone after its next sync.');
      return;
    }
    // A discarded tombstone returns the newer stored row; it replaces ours so the next try can win.
    if (res.reason === 'discarded') replaceRow(res.row);
    confirmBox.hidden = true;
    failForm(`${res.message} The transaction was not deleted.`);
  });

  return form;
}

// Swap one row in memory with what the server returned, then redraw everything that shows it.
function replaceRow(newRow) {
  if (newRow.deleted) state.txns = state.txns.filter((t) => t.id !== newRow.id);
  else state.txns = state.txns.map((t) => (t.id === newRow.id ? newRow : t));
  renderTable();
  paintDashboard();
}

// ---------------------------------------------------------------- wiring
function bindEvents() {
  $('login-form').addEventListener('submit', onLoginSubmit);
  $('signout').addEventListener('click', () => withPanelClosed(signOut));
  $('refresh').addEventListener('click', () => loadData({ reloadDirectory: true }));
  $('scope-personal').addEventListener('click', () => {
    if (state.scope.mode !== 'personal') switchScope({ mode: 'personal', groupId: null });
  });
  $('scope-group').addEventListener('click', () => {
    const id = $('group-pick').value || state.lastGroupId || (state.directory.groups[0] && state.directory.groups[0].id);
    if (id && !(state.scope.mode === 'group' && state.scope.groupId === id)) switchScope({ mode: 'group', groupId: id });
  });
  $('group-pick').addEventListener('change', () => {
    const id = $('group-pick').value;
    if (id) switchScope({ mode: 'group', groupId: id });
  });

  $('banner-close').addEventListener('click', hideBanner);
  $('banner-retry').addEventListener('click', () => { const retry = bannerRetry; if (retry) retry(); });

  for (const id of ['f-from', 'f-to', 'f-member', 'f-wallet', 'f-category', 'f-type', 'f-business']) $(id).addEventListener('change', readFilters);
  $('f-search').addEventListener('input', readFilters);
  $('filters').addEventListener('submit', (e) => e.preventDefault());
  $('f-clear').addEventListener('click', clearFilters);
  $('export').addEventListener('click', exportCsv);
  $('show-more').addEventListener('click', () => { state.visible += ROWS_PER_PAGE; renderTable(); });

  $('txn-body').addEventListener('click', (e) => {
    const tr = e.target.closest('tr[data-id]');
    if (tr) openPanel(tr.dataset.id);
  });

  $('panel-close').addEventListener('click', () => withPanelClosed(() => {}));
  $('discard-yes').addEventListener('click', () => {
    const next = panel.afterDiscard;
    panel.afterDiscard = null;
    panel.dirty = false;
    $('panel-discard').hidden = true;
    if (next) next();
  });
  $('discard-no').addEventListener('click', () => {
    panel.afterDiscard = null;
    $('panel-discard').hidden = true;
    const first = document.querySelector('#edit-form input, #edit-form select');
    if (first) first.focus();
  });
  document.addEventListener('keydown', (e) => {
    if (e.key === 'Escape' && panelOpen() && !panel.saving) withPanelClosed(() => {});
  });

  const tabs = ['transactions', 'dashboard'];
  for (const t of tabs) {
    const btn = $(`tab-btn-${t}`);
    btn.addEventListener('click', () => showTab(t));
    btn.addEventListener('keydown', (e) => {
      const i = tabs.indexOf(t);
      let j = null;
      if (e.key === 'ArrowRight') j = (i + 1) % tabs.length;
      else if (e.key === 'ArrowLeft') j = (i + tabs.length - 1) % tabs.length;
      else if (e.key === 'Home') j = 0;
      else if (e.key === 'End') j = tabs.length - 1;
      if (j === null) return;
      e.preventDefault();
      showTab(tabs[j]);
      $(`tab-btn-${tabs[j]}`).focus();
    });
  }
}

async function main() {
  bindEvents();
  if (!configured()) { showView('setup'); return; }
  state.client = makeClient(config.SUPABASE_URL, config.SUPABASE_ANON_KEY);
  // An expired or revoked session (for example signed out in another tab) sends you back to sign-in.
  state.client.auth.onAuthStateChange((event) => {
    if (event === 'SIGNED_OUT' && state.me) {
      setTimeout(() => { if (state.me) { resetSession(); showLogin('You were signed out. Sign in again.'); } }, 0);
    }
  });
  try {
    const { data, error } = await state.client.auth.getSession();
    if (error) throw error;
    if (data.session) { await enter(data.session.user); return; }
  } catch (e) {
    showLogin(`Could not restore your session: ${errText(e)}`);
    return;
  }
  showLogin('');
}

main();
