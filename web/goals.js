// The words the Goals card uses (RFC-011). Pure: no DOM. Same sentences as the phone's GoalText (Goals.kt), and the
// parity vector in test-goals.js holds both to it. Information, not advice: nothing here says "deductible".
import { formatPesos } from './money.js';
import { MONTH_SHORT, isDateText } from './stats.js';

// '2027-02-25' -> 'Feb 25, 2027'. Anything that is not a real date comes back as it was.
export function dateLabel(date) {
  if (!isDateText(date)) return String(date ?? '');
  return `${MONTH_SHORT[Number(date.slice(5, 7)) - 1]} ${Number(date.slice(8, 10))}, ${date.slice(0, 4)}`;
}

// "₱2,900.00 of ₱12,000.00 · 24% saved"
export function goalSummary(p) {
  if (p.plan.kind === 'wallet') return `Target ${formatPesos(p.target)}`;
  if (p.target <= 0) return `${formatPesos(p.saved)} saved · no target set`;
  return `${formatPesos(p.saved)} of ${formatPesos(p.target)} · ${p.percent}% saved`;
}

// The state in words, so it never depends on colour.
export function goalStatus(p) {
  switch (p.plan.kind) {
    case 'wallet': return 'Savings wallet unavailable';
    case 'notarget': return 'No target set';
    case 'reached': return 'Goal reached';
    default: return `${formatPesos(p.left)} left`;
  }
}

// "Needed per month: ₱3,033.34 (3 months left)", "No deadline", ...; null when there is nothing to add.
export function goalPlanText(p) {
  const plan = p.plan;
  switch (plan.kind) {
    case 'wallet': return 'Edit this goal on your phone to choose another savings wallet.';
    case 'nodeadline': return 'No deadline';
    case 'overdue': return `Deadline passed (${dateLabel(p.goal.deadline)})`;
    case 'monthly': return `Needed per month: ${formatPesos(plan.perMonth)} (${plan.months} ${plan.months === 1 ? 'month' : 'months'} left)`;
    default: return null;
  }
}

// "Deadline: Feb 25, 2027", or null when there is none.
export function goalDeadlineText(goal) {
  return isDateText(goal.deadline) ? `Deadline: ${dateLabel(goal.deadline)}` : null;
}
