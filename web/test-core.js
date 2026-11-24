// Checks for the Transactions tab logic that has no DOM: filters, edit validation, loading and writing.
import { applyFilters, sortTxns, NO_CATEGORY } from './filters.js';
import { validateEdit, nextUpdatedAt, isRealDate } from './edit.js';
import { fetchAllPages, loadScope, scopeMembers, updateOwnTxn, tombstoneTxn, normalizeTxn, signedPhotoUrl, PAGE_SIZE } from './data.js';
import { categoryLabel, CATEGORIES, ALL_CATEGORIES } from './categories.js';

let n = 0;
const tx = (over = {}) => {
  n += 1;
  return {
    id: `id-${String(n).padStart(5, '0')}`, owner_id: 'me', type: 'expense', wallet_id: 'w1', to_wallet_id: null, amount: 10000,
    date: '2026-11-20', category: 'food', merchant: 'Jollibee', note: null, is_business: false, source: 'manual',
    ref_no: null, receipt_path: null, seller_tin: null, invoice_no: null, vatable_sales: null, vat: null,
    vat_exempt: null, deleted: false, updated_at: 100, ...over,
  };
};

// A tiny stand-in for the supabase-js query builder: just enough for data.js.
function fakeClient({ tables = {}, failOn = null, updateRows = null, storage = null } = {}) {
  const calls = [];
  const client = {
    calls,
    from(table) {
      const q = { table, filters: [], order: [], op: 'select', patch: null };
      const builder = {
        select() { return builder; },
        update(p) { q.op = 'update'; q.patch = p; return builder; },
        in(col, vals) { q.filters.push((r) => vals.includes(r[col])); return builder; },
        eq(col, val) { q.filters.push((r) => r[col] === val); return builder; },
        order(col) { q.order.push(col); return builder; },
        range(a, b) { q.range = [a, b]; return builder; },
        then(resolve, reject) {
          calls.push({ table, op: q.op, range: q.range, patch: q.patch });
          if (failOn && failOn(q)) return Promise.resolve({ data: null, error: { message: 'boom' } }).then(resolve, reject);
          if (q.op === 'update') {
            const data = updateRows ? updateRows(q) : [];
            return Promise.resolve({ data, error: null }).then(resolve, reject);
          }
          let rows = (tables[table] || []).filter((r) => q.filters.every((f) => f(r)));
          for (const col of [...q.order].reverse()) rows = [...rows].sort((a, b) => (a[col] < b[col] ? -1 : a[col] > b[col] ? 1 : 0));
          if (q.range) rows = rows.slice(q.range[0], q.range[1] + 1);
          return Promise.resolve({ data: rows, error: null }).then(resolve, reject);
        },
      };
      return builder;
    },
    storage: { from: () => ({ createSignedUrl: async () => storage || { data: { signedUrl: 'https://x.supabase.co/sign/abc?token=t' }, error: null } }) },
  };
  return client;
}

// the async checks of a test module run after tests(t) returns, so they register through t.later
export function tests(t) {
  // ------------------------------------------------------------ categories
  t.check('categories: slugs match the phone and labels come back', () => {
    t.eq(CATEGORIES.expense.map((c) => c.slug), ['food', 'groceries', 'transport', 'bills', 'shopping', 'health', 'education', 'entertainment', 'travel', 'other']);
    t.eq(CATEGORIES.income.map((c) => c.slug), ['salary', 'business', 'gift', 'other']);
    t.eq(categoryLabel('food'), 'Food & Dining');
    t.eq(categoryLabel('bills'), 'Bills & Utilities');
    t.eq(categoryLabel('salary'), 'Salary');
    t.eq(categoryLabel('other'), 'Other');
    t.eq(categoryLabel(null), '');
    t.eq(categoryLabel('mystery'), 'mystery');
    t.eq(ALL_CATEGORIES.filter((c) => c.slug === 'other').length, 1);
  });

  // ------------------------------------------------------------ filters
  t.check('filters: date range is inclusive on both ends', () => {
    const rows = [tx({ date: '2026-11-01' }), tx({ date: '2026-11-15' }), tx({ date: '2026-11-30' }), tx({ date: '2026-12-01' })];
    t.eq(applyFilters(rows, { from: '2026-11-01', to: '2026-11-30' }).map((r) => r.date), ['2026-11-01', '2026-11-15', '2026-11-30']);
    t.eq(applyFilters(rows, { from: '2026-11-16' }).length, 2);
    t.eq(applyFilters(rows, { to: '2026-11-14' }).length, 1);
    t.eq(applyFilters(rows, { from: '2026-12-01', to: '2026-11-01' }).length, 0);
  });
  t.check('filters: member, type, category, no-category, business', () => {
    const rows = [
      tx({ owner_id: 'a', category: 'food' }), tx({ owner_id: 'b', category: 'travel', is_business: true }),
      tx({ owner_id: 'b', type: 'income', category: 'salary' }), tx({ owner_id: 'a', type: 'transfer', category: null, to_wallet_id: 'w2' }),
    ];
    t.eq(applyFilters(rows, { member: 'b' }).length, 2);
    t.eq(applyFilters(rows, { type: 'income' }).length, 1);
    t.eq(applyFilters(rows, { category: 'travel' }).length, 1);
    t.eq(applyFilters(rows, { category: NO_CATEGORY }).length, 1);
    t.eq(applyFilters(rows, { business: true }).length, 1);
    t.eq(applyFilters(rows, { member: 'b', business: true, type: 'expense' }).length, 1);
  });
  t.check('filters: wallet matches the source or the destination wallet of a transfer', () => {
    const rows = [tx({ wallet_id: 'w1' }), tx({ type: 'transfer', wallet_id: 'w2', to_wallet_id: 'w1', category: null }), tx({ wallet_id: 'w3' })];
    t.eq(applyFilters(rows, { wallet: 'w1' }).length, 2);
  });
  t.check('filters: search is case-insensitive across merchant, note, ref, label, member, wallet and amount', () => {
    const rows = [
      tx({ merchant: 'Mercury Drug', category: 'health' }), tx({ note: 'birthday cake' }), tx({ ref_no: 'GC12345' }),
      tx({ amount: 123456 }), tx({ owner_id: 'u2' }), tx({ wallet_id: 'w9' }),
    ];
    const names = { members: new Map([['u2', 'Dad']]), wallets: new Map([['w9', 'Maya']]) };
    t.eq(applyFilters(rows, { search: 'MERCURY' }, names).length, 1);
    t.eq(applyFilters(rows, { search: 'health' }, names).length, 1);
    t.eq(applyFilters(rows, { search: 'Birthday' }, names).length, 1);
    t.eq(applyFilters(rows, { search: 'gc123' }, names).length, 1);
    t.eq(applyFilters(rows, { search: '1234.56' }, names).length, 1);
    t.eq(applyFilters(rows, { search: 'dad' }, names).length, 1);
    t.eq(applyFilters(rows, { search: 'maya' }, names).length, 1);
    t.eq(applyFilters(rows, { search: '  ' }, names).length, rows.length);
    t.eq(applyFilters(rows, { search: 'zzz' }, names).length, 0);
  });
  t.check('filters: never returns deleted rows; does not modify the input', () => {
    const rows = [tx(), tx({ deleted: true })];
    const copy = JSON.stringify(rows);
    t.eq(applyFilters(rows, {}).length, 1);
    t.eq(JSON.stringify(rows), copy);
  });
  t.check('sortTxns: newest day first, then latest edit, then id; input untouched', () => {
    const a = tx({ date: '2026-11-01', updated_at: 5 });
    const b = tx({ date: '2026-11-02', updated_at: 1 });
    const c = tx({ date: '2026-11-02', updated_at: 9 });
    const input = [a, b, c];
    t.eq(sortTxns(input).map((r) => r.id), [c.id, b.id, a.id]);
    t.eq(input.map((r) => r.id), [a.id, b.id, c.id]);
  });

  // ------------------------------------------------------------ edit validation
  const own = ['w1', 'w2'];
  const form = (over = {}) => ({
    type: 'expense', date: '2026-11-20', amount: '1,234.50', wallet_id: 'w1', to_wallet_id: '', category: 'food',
    merchant: '  Jollibee  ', note: '', is_business: true, seller_tin: '', invoice_no: '', ref_no: '',
    vatable_sales: '', vat: '', vat_exempt: '', ...over,
  });
  const original = tx({ wallet_id: 'w1' });
  const run = (over) => validateEdit(form(over), { ownWalletIds: own, original });

  t.check('validateEdit: a good form becomes integer centavos, trimmed text, empty -> null', () => {
    const { errors, payload } = run({});
    t.eq(errors, {});
    t.eq(payload.amount, 123450);
    t.eq(payload.merchant, 'Jollibee');
    t.eq(payload.note, null);
    t.eq(payload.to_wallet_id, null);
    t.eq(payload.vat, null);
    t.eq(payload.is_business, true);
  });
  t.check('validateEdit: amount is strict like the phone', () => {
    for (const bad of ['', 'abc', '1.234', '12,34', '-5', '9999999999999']) t.ok(run({ amount: bad }).errors.amount, `"${bad}"`);
    t.eq(run({ amount: '0' }).payload.amount, 0);
    t.eq(run({ amount: '₱5' }).payload.amount, 500);
  });
  t.check('validateEdit: date must be a real day', () => {
    for (const bad of ['', '2026-13-01', '2026-02-30', '20-11-2026', 'x']) t.ok(run({ date: bad }).errors.date, bad);
    t.ok(isRealDate('2028-02-29'));
    t.ok(!isRealDate('2027-02-29'));
  });
  t.check('validateEdit: wallet must be one of mine (or the row\'s own archived wallet)', () => {
    t.ok(run({ wallet_id: 'someone-elses' }).errors.wallet_id);
    t.ok(run({ wallet_id: '' }).errors.wallet_id);
    const archived = validateEdit(form({ wallet_id: 'old' }), { ownWalletIds: own, original: tx({ wallet_id: 'old' }) });
    t.eq(archived.errors, {});
  });
  t.check('validateEdit: transfers need two different wallets and lose their category', () => {
    t.ok(run({ type: 'transfer', to_wallet_id: '' }).errors.to_wallet_id);
    t.ok(run({ type: 'transfer', to_wallet_id: 'w1' }).errors.to_wallet_id);
    const ok = run({ type: 'transfer', to_wallet_id: 'w2', category: 'food' });
    t.eq(ok.errors, {});
    t.eq([ok.payload.to_wallet_id, ok.payload.category], ['w2', null]);
    t.eq(run({ type: 'expense', to_wallet_id: 'w2' }).payload.to_wallet_id, null);
  });
  t.check('validateEdit: category must fit the type; an unknown old category may stay', () => {
    t.ok(run({ type: 'income', category: 'food' }).errors.category);
    t.eq(run({ type: 'income', category: 'salary' }).errors, {});
    t.eq(run({ category: '' }).payload.category, null);
    const old = tx({ category: 'legacy' });
    t.eq(validateEdit(form({ category: 'legacy' }), { ownWalletIds: own, original: old }).errors, {});
    t.ok(validateEdit(form({ type: 'income', category: 'legacy' }), { ownWalletIds: own, original: old }).errors.category);
  });
  t.check('validateEdit: VAT fields are optional money; length limits hold', () => {
    t.eq(run({ vat: '10.71', vatable_sales: '1,000', vat_exempt: '0' }).payload.vat, 1071);
    t.eq(run({ vat: '10.71', vatable_sales: '1,000', vat_exempt: '0' }).payload.vatable_sales, 100000);
    t.ok(run({ vat: 'ten' }).errors.vat);
    t.ok(run({ merchant: 'x'.repeat(121) }).errors.merchant);
    t.ok(run({ note: 'x'.repeat(501) }).errors.note);
    t.eq(run({ merchant: 'x'.repeat(120) }).errors, {});
  });
  t.check('nextUpdatedAt: now, but never at or below what the row already has', () => {
    t.eq(nextUpdatedAt(1000, { updated_at: 500 }), 1000);
    t.eq(nextUpdatedAt(1000, { updated_at: 5000 }), 5001);
    t.eq(nextUpdatedAt(1000, { updated_at: 1000 }), 1001);
    t.eq(nextUpdatedAt(1000, {}), 1000);
    t.eq(nextUpdatedAt(1000, null), 1000);
  });

  // ------------------------------------------------------------ data: members and normalising
  const directory = {
    groups: [{ id: 'g1', name: 'Family' }],
    memberIds: (g) => (g === 'g1' ? ['me', 'dad', 'ana'] : []),
    displayName: new Map([['me', 'Matthew'], ['dad', 'Dad'], ['ana', '']]),
  };
  t.check('scopeMembers: personal is just me; group puts me first, then by name, with a stand-in for no name', () => {
    t.eq(scopeMembers(directory, { id: 'me', email: 'm@x.ph' }, { mode: 'personal' }), [{ id: 'me', name: 'Matthew' }]);
    t.eq(scopeMembers(directory, { id: 'me', email: 'm@x.ph' }, { mode: 'group', groupId: 'g1' }).map((m) => m.name), ['Matthew', 'Dad', 'Member ana']);
    t.eq(scopeMembers({ ...directory, displayName: new Map() }, { id: 'me', email: 'm@x.ph' }, { mode: 'personal' })[0].name, 'm@x.ph');
    t.eq(scopeMembers({ ...directory, displayName: new Map() }, { id: 'me', email: '' }, { mode: 'personal' })[0].name, 'You');
  });
  t.check('normalizeTxn: bigint-ish values become numbers, nulls stay null', () => {
    const r = normalizeTxn({ amount: '1500', vat: null, vatable_sales: '100', vat_exempt: undefined, updated_at: '99', deleted: false, is_business: null });
    t.eq([r.amount, r.vat, r.vatable_sales, r.vat_exempt, r.updated_at, r.is_business], [1500, null, 100, null, 99, false]);
  });

  // ------------------------------------------------------------ data: async (registered with t.later)
  const later = t.later || (() => {});

  later('fetchAllPages: 2500 rows come in 3 pages of 1000, 1000, 500', async () => {
    const rows = Array.from({ length: 2500 }, (_, i) => ({ id: i }));
    const ranges = [];
    const got = await fetchAllPages(async (a, b) => { ranges.push([a, b]); return { data: rows.slice(a, b + 1), error: null }; });
    t.eq(got.length, 2500);
    t.eq(ranges, [[0, 999], [1000, 1999], [2000, 2999]]);
  });
  later('fetchAllPages: exactly 1000 rows asks for one more page; an error throws instead of returning part of the list', async () => {
    const rows = Array.from({ length: 1000 }, (_, i) => ({ id: i }));
    const ranges = [];
    await fetchAllPages(async (a, b) => { ranges.push([a, b]); return { data: rows.slice(a, b + 1), error: null }; });
    t.eq(ranges.length, 2);
    let threw = '';
    try {
      await fetchAllPages(async (a) => (a === 0 ? { data: rows, error: null } : { data: null, error: { message: 'net down' } }));
    } catch (e) { threw = e.message; }
    t.eq(threw, 'net down');
  });
  later('loadScope: personal asks only for my rows; group asks for every member; deleted rows are not requested; 2500 rows all arrive', async () => {
    const mine = Array.from({ length: 2500 }, (_, i) => tx({ owner_id: 'me', amount: i }));
    const theirs = [tx({ owner_id: 'dad' }), tx({ owner_id: 'stranger' }), tx({ owner_id: 'me', deleted: true })];
    const wallets = [{ id: 'w1', owner_id: 'me', name: 'Cash', type: 'cash', opening_balance: '0', deleted: false }, { id: 'w2', owner_id: 'dad', name: 'Cash', type: 'cash', opening_balance: 5, deleted: true }];
    const client = fakeClient({ tables: { transactions: [...mine, ...theirs], wallets } });
    const personal = await loadScope(client, directory, { id: 'me', email: '' }, { mode: 'personal', groupId: null });
    t.eq(personal.txns.length, 2500);
    t.ok(personal.txns.every((r) => r.owner_id === 'me' && r.deleted === false));
    t.eq(personal.wallets.map((w) => w.id), ['w1']);
    t.eq(client.calls.filter((c) => c.table === 'transactions').length, 3);
    const group = await loadScope(client, directory, { id: 'me', email: '' }, { mode: 'group', groupId: 'g1' });
    t.eq(group.txns.length, 2501);
    t.ok(!group.txns.some((r) => r.owner_id === 'stranger'));
    t.eq(group.wallets.length, 2);
    t.eq(group.wallets.find((w) => w.id === 'w2').deleted, true);
    t.eq(PAGE_SIZE, 1000);
  });
  later('loadScope: a failed page rejects, so the caller keeps the old data', async () => {
    const client = fakeClient({ tables: { transactions: [tx()] }, failOn: (q) => q.table === 'wallets' });
    let threw = false;
    try { await loadScope(client, directory, { id: 'me', email: '' }, { mode: 'personal' }); } catch (e) { threw = true; }
    t.ok(threw);
  });
  later('updateOwnTxn: scoped by id and owner; success returns the stored row', async () => {
    const seen = [];
    const client = fakeClient({ updateRows: (q) => { seen.push(q.patch); return [{ ...tx({ id: 'a' }), ...q.patch }]; } });
    const res = await updateOwnTxn(client, 'a', 'me', { amount: 500 }, 777);
    t.ok(res.ok);
    t.eq(seen[0], { amount: 500, updated_at: 777 });
    t.eq(res.row.updated_at, 777);
  });
  later('updateOwnTxn: zero rows back (RLS hides it) is a refusal, never success', async () => {
    const res = await updateOwnTxn(fakeClient({ updateRows: () => [] }), 'a', 'someone', { amount: 1 }, 5);
    t.eq([res.ok, res.reason], [false, 'refused']);
  });
  later('updateOwnTxn: a server error is reported; a newer stored row (LWW) is reported as discarded', async () => {
    const err = await updateOwnTxn(fakeClient({ failOn: () => true }), 'a', 'me', {}, 5);
    t.eq([err.ok, err.reason, err.message], [false, 'error', 'boom']);
    const lww = await updateOwnTxn(fakeClient({ updateRows: () => [tx({ id: 'a', updated_at: 9999 })] }), 'a', 'me', { amount: 1 }, 5);
    t.eq([lww.ok, lww.reason, lww.row.updated_at], [false, 'discarded', 9999]);
  });
  later('tombstoneTxn sets deleted = true with the new updated_at', async () => {
    let patch = null;
    const client = fakeClient({ updateRows: (q) => { patch = q.patch; return [{ ...tx({ id: 'a' }), ...q.patch }]; } });
    const res = await tombstoneTxn(client, 'a', 'me', 4242);
    t.eq(patch, { deleted: true, updated_at: 4242 });
    t.ok(res.ok);
    t.eq(res.row.deleted, true);
  });
  later('signedPhotoUrl: accepts https links, rejects other schemes and errors', async () => {
    t.eq(await signedPhotoUrl(fakeClient(), 'me/a.jpg'), 'https://x.supabase.co/sign/abc?token=t');
    for (const storage of [{ data: { signedUrl: 'javascript:alert(1)' }, error: null }, { data: null, error: { message: 'nope' } }, { data: {}, error: null }]) {
      let threw = false;
      try { await signedPhotoUrl(fakeClient({ storage }), 'me/a.jpg'); } catch (e) { threw = true; }
      t.ok(threw);
    }
  });
}
