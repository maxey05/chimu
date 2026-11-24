// Money is integer centavos everywhere (RULES 7). This file is the edge: numbers in, text out, and
// typed text in, centavos out. Same rules as android/.../Money.kt.

const MAX_WHOLE_DIGITS = 12; // keeps pesos * 100 far inside Number.MAX_SAFE_INTEGER
const GROUPED = /^[0-9]{1,3}(,[0-9]{3})+$/; // `1,234` or `12,345,678`, never `12,34`

function assertCentavos(centavos) {
  if (typeof centavos !== 'number' || !Number.isSafeInteger(centavos)) {
    throw new TypeError(`money must be integer centavos, got ${String(centavos)}`);
  }
}

function groupThousands(digits) {
  return digits.replace(/\B(?=(\d{3})+(?!\d))/g, ',');
}

// 123450 -> '₱1,234.50'; -123450 -> '-₱1,234.50'. Integer centavos only.
export function formatPesos(centavos) {
  assertCentavos(centavos);
  const abs = Math.abs(centavos);
  const pesos = Math.floor(abs / 100);
  const cents = String(abs % 100).padStart(2, '0');
  return `${centavos < 0 ? '-' : ''}₱${groupThousands(String(pesos))}.${cents}`;
}

// 123450 -> '1234.50'. No symbol, no thousands separators: for CSV and for filling an input.
export function pesosPlain(centavos) {
  assertCentavos(centavos);
  const abs = Math.abs(centavos);
  return `${centavos < 0 ? '-' : ''}${Math.floor(abs / 100)}.${String(abs % 100).padStart(2, '0')}`;
}

// Row amounts arrive from PostgREST as numbers (bigint) but be defensive: null/undefined/garbage -> 0,
// numeric strings -> number, fractions rounded. Use on raw rows before summing or formatting.
export function normCentavos(value) {
  const n = typeof value === 'string' && value.trim() !== '' ? Number(value) : value;
  if (typeof n !== 'number' || !Number.isFinite(n)) return 0;
  return Math.round(n);
}

// What a person types -> centavos, or null when it is not a plain amount.
// '1,234.50' -> 123450, '₱5' -> 500, '.5' -> 50. Rejects letters, a third decimal, misplaced commas,
// two dots, blank, more than 12 whole digits, and negatives (unless allowNegative).
export function parsePesos(input, { allowNegative = false } = {}) {
  if (typeof input !== 'string') return null;
  let s = input.trim();
  let negative = false;
  if (s.startsWith('-')) {
    if (!allowNegative) return null;
    negative = true;
    s = s.slice(1).trim();
  }
  if (s.startsWith('₱')) s = s.slice(1).trim();
  else if (/^php/i.test(s)) s = s.slice(3).trim();

  const dot = s.indexOf('.');
  const whole = dot < 0 ? s : s.slice(0, dot);
  const fraction = dot < 0 ? '' : s.slice(dot + 1);

  if (fraction.length > 2 || !/^[0-9]*$/.test(fraction)) return null;
  if (whole === '' && fraction === '') return null;
  if (whole.includes(',')) {
    if (!GROUPED.test(whole)) return null;
  } else if (!/^[0-9]*$/.test(whole)) {
    return null;
  }
  const wholeDigits = whole.replace(/,/g, '');
  if (wholeDigits.length > MAX_WHOLE_DIGITS) return null;

  const pesos = wholeDigits === '' ? 0 : Number(wholeDigits);
  const cents = fraction === '' ? 0 : Number(fraction.padEnd(2, '0'));
  const total = pesos * 100 + cents;
  return negative ? -total : total;
}
