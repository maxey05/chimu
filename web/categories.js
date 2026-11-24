// Fixed category lists. Mirrors android/app/src/main/java/com/chimu/Models.kt (Categories) and
// DESIGN.md -> Categories. Rows store the lowercase slug; this file turns slugs into labels.
export const CATEGORIES = {
  expense: [
    { slug: 'food', label: 'Food & Dining' },
    { slug: 'groceries', label: 'Groceries' },
    { slug: 'transport', label: 'Transport' },
    { slug: 'bills', label: 'Bills & Utilities' },
    { slug: 'shopping', label: 'Shopping' },
    { slug: 'health', label: 'Health' },
    { slug: 'education', label: 'Education' },
    { slug: 'entertainment', label: 'Entertainment' },
    { slug: 'travel', label: 'Travel' },
    { slug: 'other', label: 'Other' },
  ],
  income: [
    { slug: 'salary', label: 'Salary' },
    { slug: 'business', label: 'Business' },
    { slug: 'gift', label: 'Gift' },
    { slug: 'other', label: 'Other' },
  ],
};

// Every slug from either list, once ('other' is shared), expense list first.
export const ALL_CATEGORIES = [...CATEGORIES.expense, ...CATEGORIES.income.filter((c) => c.slug !== 'other')];

// Label for a stored slug. null/'' -> '' (transfers and unscanned rows have no category).
// An unknown slug is shown as-is rather than hidden.
export function categoryLabel(slug) {
  if (slug === null || slug === undefined || slug === '') return '';
  const hit = ALL_CATEGORIES.find((c) => c.slug === slug);
  return hit ? hit.label : String(slug);
}

// The categories a row of this type may take (transfers have none).
export function categoriesFor(type) {
  if (type === 'expense') return CATEGORIES.expense;
  if (type === 'income') return CATEGORIES.income;
  return [];
}
