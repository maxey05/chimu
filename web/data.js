// Loading and writing through the Supabase client. The client is passed in, so tests can hand in a fake.
// Reads are done once per scope (personal, or a chosen group) and per refresh: everything non-deleted
// for the scope comes into memory and the Transactions tab filters it there.
import { normCentavos } from './money.js';

export const PAGE_SIZE = 1000;

// Runs makeQuery(from, to) for 0..999, 1000..1999, ... until a page is shorter than PAGE_SIZE.
// Throws the first error: a half-loaded list must never look like a complete one (RULES 20).
export async function fetchAllPages(makeQuery, pageSize = PAGE_SIZE) {
  const all = [];
  for (let from = 0; ; from += pageSize) {
    const { data, error } = await makeQuery(from, from + pageSize - 1);
    if (error) throw new Error(error.message || String(error));
    const rows = data || [];
    all.push(...rows);
    if (rows.length < pageSize) return all;
  }
}

// Raw PostgREST row -> row with numbers the rest of the page can trust. Fields keep the DB names.
export function normalizeTxn(row) {
  const nullableMoney = (v) => (v === null || v === undefined ? null : normCentavos(v));
  return {
    ...row,
    amount: normCentavos(row.amount),
    vatable_sales: nullableMoney(row.vatable_sales),
    vat: nullableMoney(row.vat),
    vat_exempt: nullableMoney(row.vat_exempt),
    updated_at: normCentavos(row.updated_at),
    deleted: row.deleted === true,
    is_business: row.is_business === true,
  };
}

export function normalizeWallet(row) {
  return { ...row, opening_balance: normCentavos(row.opening_balance), deleted: row.deleted === true };
}

// group_id and category stay null when empty: null means personal / overall.
export function normalizeBudget(row) {
  return {
    ...row,
    group_id: row.group_id || null,
    category: row.category || null,
    monthly_limit: normCentavos(row.monthly_limit),
    updated_at: normCentavos(row.updated_at),
    deleted: row.deleted === true,
  };
}

// The groups I am in, who is in them, and everyone's display name. RLS already limits all three.
export async function loadDirectory(client) {
  const groups = await fetchAllPages((a, b) =>
    client.from('groups').select('id,name,owner_id').order('name').order('id').range(a, b));
  const memberRows = await fetchAllPages((a, b) =>
    client.from('group_members').select('group_id,user_id,role').order('group_id').order('user_id').range(a, b));
  const profileRows = await fetchAllPages((a, b) =>
    client.from('profiles').select('id,display_name').order('id').range(a, b));
  return {
    groups,
    memberIds: (groupId) => memberRows.filter((m) => m.group_id === groupId).map((m) => m.user_id),
    displayName: new Map(profileRows.map((p) => [p.id, (p.display_name || '').trim()])),
  };
}

// Members of the scope, me first, then by name. A person without a display name gets a short stand-in.
export function scopeMembers(directory, me, scope) {
  const nameOf = (id) => {
    const n = directory.displayName.get(id);
    if (n) return n;
    return id === me.id ? (me.email || 'You') : `Member ${id.slice(0, 4)}`;
  };
  if (scope.mode !== 'group') return [{ id: me.id, name: nameOf(me.id) }];
  const ids = new Set(directory.memberIds(scope.groupId));
  ids.add(me.id);
  const others = [...ids].filter((id) => id !== me.id).map((id) => ({ id, name: nameOf(id) }));
  others.sort((a, b) => a.name.localeCompare(b.name) || (a.id < b.id ? -1 : 1));
  return [{ id: me.id, name: nameOf(me.id) }, ...others];
}

// Everything one scope needs. Returns { members, txns (non-deleted), wallets (all, archived flagged) }.
export async function loadScope(client, directory, me, scope) {
  const members = scopeMembers(directory, me, scope);
  const ownerIds = members.map((m) => m.id);
  const txRows = await fetchAllPages((a, b) =>
    client.from('transactions').select('*').in('owner_id', ownerIds).eq('deleted', false)
      .order('id').range(a, b));
  const walletRows = await fetchAllPages((a, b) =>
    client.from('wallets').select('*').in('owner_id', ownerIds).order('id').range(a, b));
  return {
    members,
    txns: txRows.map(normalizeTxn).filter((t) => !t.deleted),
    wallets: walletRows.map(normalizeWallet),
  };
}

// The budgets of one scope (RFC-009), live rows only. Personal = mine with no group; group = every budget of
// that group, whoever owns it. RLS also lets me read other members' personal budgets and budgets of
// groups I share with them, so the scope is filtered here as well as by the dashboard. A failed page
// rejects, so the caller never shows part of the list as the whole list (RULES 20).
export async function loadBudgets(client, me, scope) {
  const rows = await fetchAllPages((a, b) => {
    const q = client.from('budgets').select('*').eq('deleted', false);
    const scoped = scope.mode === 'group' ? q.eq('group_id', scope.groupId) : q.eq('owner_id', me.id).is('group_id', null);
    return scoped.order('id').range(a, b);
  });
  return rows.map(normalizeBudget).filter((b) => !b.deleted);
}

// Writes one of MY rows. The update is scoped by id and owner; RLS is the real gate. PostgREST answers
// 200 with zero rows when RLS hides the row, so "no row came back" is treated as a refusal, never as success.
// If the server's last-writer-wins guard kept a newer stored row, the returned updated_at differs from
// what was sent: that is reported as `discarded` with the stored row.
async function updateOwn(client, table, normalize, noun, id, ownerId, changes, updatedAt) {
  const sent = { ...changes, updated_at: updatedAt };
  const { data, error } = await client.from(table).update(sent)
    .eq('id', id).eq('owner_id', ownerId).select();
  if (error) return { ok: false, reason: 'error', message: error.message || String(error) };
  if (!data || data.length === 0) return { ok: false, reason: 'refused', message: `The server did not accept the change. This ${noun} may not be yours, or it may have been removed.` };
  const row = normalize(data[0]);
  if (row.updated_at !== updatedAt) return { ok: false, reason: 'discarded', row, message: `A newer change to this ${noun} already exists (for example from the phone, or a clock that runs ahead), so yours was not applied.` };
  return { ok: true, row };
}

export function updateOwnTxn(client, id, ownerId, changes, updatedAt) {
  return updateOwn(client, 'transactions', normalizeTxn, 'row', id, ownerId, changes, updatedAt);
}

// Delete = tombstone (RULES 9): deleted = true with a fresh updated_at.
export function tombstoneTxn(client, id, ownerId, updatedAt) {
  return updateOwnTxn(client, id, ownerId, { deleted: true }, updatedAt);
}

// Budgets (RFC-009): the same write rules, for budgets I own. `changes` is { category, monthly_limit }.
export function updateOwnBudget(client, id, ownerId, changes, updatedAt) {
  return updateOwn(client, 'budgets', normalizeBudget, 'budget', id, ownerId, changes, updatedAt);
}

export function tombstoneBudget(client, id, ownerId, updatedAt) {
  return updateOwnBudget(client, id, ownerId, { deleted: true }, updatedAt);
}

// A new budget of mine: a fresh id, owner = me, group_id null for a personal one. The insert returns the
// stored row (.select()); if nothing comes back the server did not take it. RLS rejects a group I am not in.
export async function createBudget(client, ownerId, { groupId, category, monthlyLimit }, updatedAt, id = globalThis.crypto.randomUUID()) {
  const sent = {
    id, owner_id: ownerId, group_id: groupId || null, category: category || null,
    monthly_limit: monthlyLimit, updated_at: updatedAt, deleted: false,
  };
  const { data, error } = await client.from('budgets').insert(sent).select();
  if (error) return { ok: false, reason: 'error', message: error.message || String(error) };
  if (!data || data.length === 0) return { ok: false, reason: 'refused', message: 'The server did not accept the new budget. You may not be in that group any more.' };
  return { ok: true, row: normalizeBudget(data[0]) };
}

export async function signedPhotoUrl(client, path, seconds = 600) {
  const { data, error } = await client.storage.from('receipts').createSignedUrl(path, seconds);
  if (error) throw new Error(error.message || String(error));
  const url = data && (data.signedUrl || data.signedURL);
  if (!url || !/^https?:\/\//i.test(url)) throw new Error('The photo link was not valid.');
  return url;
}
