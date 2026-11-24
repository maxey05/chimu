// Client-side filtering and sorting of the loaded transactions. Pure functions, no DOM.
// The Transactions tab never re-queries per filter: it filters state.txns with these.
import { normCentavos, pesosPlain } from './money.js';
import { categoryLabel } from './categories.js';

export const NO_CATEGORY = '__none';

export const EMPTY_FILTERS = Object.freeze({
  from: '', to: '', member: '', wallet: '', category: '', type: '', business: false, search: '',
});

// newest day first, then most recently edited, then id so the order never jitters
export function sortTxns(txns) {
  return [...txns].sort((a, b) => {
    if (a.date !== b.date) return a.date < b.date ? 1 : -1;
    const ua = normCentavos(a.updated_at);
    const ub = normCentavos(b.updated_at);
    if (ua !== ub) return ub - ua;
    return a.id < b.id ? -1 : a.id > b.id ? 1 : 0;
  });
}

// names: { members: Map(id -> name), wallets: Map(id -> name) } used by the text search only.
export function applyFilters(txns, f, names = {}) {
  const needle = (f.search || '').trim().toLowerCase();
  const members = names.members || new Map();
  const wallets = names.wallets || new Map();
  return txns.filter((t) => {
    if (t.deleted === true) return false;
    if (f.from && t.date < f.from) return false;
    if (f.to && t.date > f.to) return false;
    if (f.member && t.owner_id !== f.member) return false;
    if (f.wallet && t.wallet_id !== f.wallet && t.to_wallet_id !== f.wallet) return false;
    if (f.type && t.type !== f.type) return false;
    if (f.category === NO_CATEGORY) {
      if (t.category) return false;
    } else if (f.category && t.category !== f.category) return false;
    if (f.business && t.is_business !== true) return false;
    if (needle) {
      const hay = [
        t.merchant, t.note, t.ref_no, t.invoice_no, t.seller_tin, categoryLabel(t.category),
        members.get(t.owner_id), wallets.get(t.wallet_id), t.date, pesosPlain(normCentavos(t.amount)),
      ].filter(Boolean).join('\n').toLowerCase();
      if (!hay.includes(needle)) return false;
    }
    return true;
  });
}
