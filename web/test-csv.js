// Checks for money.js, csv.js and the totals definitions (RULES 19). Runs in Node and in test.html.
import { formatPesos, pesosPlain, parsePesos, normCentavos } from './money.js';
import { buildCsv, csvTotals, csvField, safeText, CSV_COLUMNS } from './csv.js';
import { applyFilters } from './filters.js';

// Minimal RFC 4180 reader so the tests check what a spreadsheet would see. Returns an array of rows.
export function parseCsv(text) {
  const rows = [];
  let row = [];
  let field = '';
  let quoted = false;
  for (let i = 0; i < text.length; i += 1) {
    const c = text[i];
    if (quoted) {
      if (c === '"' && text[i + 1] === '"') { field += '"'; i += 1; }
      else if (c === '"') quoted = false;
      else field += c;
    } else if (c === '"') quoted = true;
    else if (c === ',') { row.push(field); field = ''; }
    else if (c === '\r' && text[i + 1] === '\n') { row.push(field); rows.push(row); row = []; field = ''; i += 1; }
    else field += c;
  }
  if (field !== '' || row.length) { row.push(field); rows.push(row); }
  return rows;
}

let n = 0;
const tx = (over = {}) => {
  n += 1;
  return {
    id: `id-${n}`, owner_id: 'u1', type: 'expense', wallet_id: 'w1', to_wallet_id: null, amount: 10000,
    date: '2026-11-20', category: 'food', merchant: 'Jollibee', note: null, is_business: false, source: 'manual',
    ref_no: null, receipt_path: null, seller_tin: null, invoice_no: null, vatable_sales: null, vat: null,
    vat_exempt: null, deleted: false, updated_at: 1, ...over,
  };
};
const ctx = {
  members: [{ id: 'u1', name: 'Matthew' }, { id: 'u2', name: 'Dad' }],
  wallets: [{ id: 'w1', name: 'Cash' }, { id: 'w2', name: 'GCash' }],
};
const col = (name) => CSV_COLUMNS.indexOf(name);
const dataRows = (csv) => parseCsv(csv).slice(1).filter((r) => r[col('type')] !== 'TOTAL');
const totalRows = (csv) => parseCsv(csv).filter((r) => r[col('type')] === 'TOTAL');
// the number a spreadsheet would read from a pesos cell, back in centavos
const centavos = (cell) => parsePesos(cell, { allowNegative: true });

export function tests(t) {
  // ------------------------------------------------------------ formatPesos / pesosPlain
  t.check('formatPesos: zero, centavos, whole pesos', () => {
    t.eq(formatPesos(0), '₱0.00');
    t.eq(formatPesos(5), '₱0.05');
    t.eq(formatPesos(99), '₱0.99');
    t.eq(formatPesos(100), '₱1.00');
    t.eq(formatPesos(123450), '₱1,234.50');
    t.eq(formatPesos(100000), '₱1,000.00');
    t.eq(formatPesos(99999), '₱999.99');
  });
  t.check('formatPesos: negatives read -₱1,234.50', () => {
    t.eq(formatPesos(-123450), '-₱1,234.50');
    t.eq(formatPesos(-5), '-₱0.05');
    t.eq(formatPesos(-100000), '-₱1,000.00');
  });
  t.check('formatPesos: 12 whole digits, exact', () => {
    t.eq(formatPesos(99999999999900), '₱999,999,999,999.00');
    t.eq(formatPesos(99999999999999), '₱999,999,999,999.99');
  });
  t.check('formatPesos and pesosPlain refuse floats, strings, null and NaN', () => {
    for (const bad of [12.5, NaN, Infinity, '100', null, undefined, 1e300]) {
      let threw = false;
      try { formatPesos(bad); } catch (e) { threw = e instanceof TypeError; }
      t.ok(threw, `formatPesos(${String(bad)}) should throw TypeError`);
      threw = false;
      try { pesosPlain(bad); } catch (e) { threw = e instanceof TypeError; }
      t.ok(threw, `pesosPlain(${String(bad)}) should throw TypeError`);
    }
  });
  t.check('pesosPlain: 2 decimals, no symbol, no thousands separators', () => {
    t.eq(pesosPlain(0), '0.00');
    t.eq(pesosPlain(5), '0.05');
    t.eq(pesosPlain(123450), '1234.50');
    t.eq(pesosPlain(-5), '-0.05');
    t.eq(pesosPlain(-123450), '-1234.50');
    t.eq(pesosPlain(99999999999900), '999999999999.00');
  });
  t.check('integer centavos add up exactly where float pesos would not (0.10 + 0.20)', () => {
    t.eq(pesosPlain(10 + 20), '0.30');
    t.ok(0.1 + 0.2 !== 0.3, 'sanity: float pesos really do drift');
  });
  t.check('normCentavos: null, strings, fractions and junk', () => {
    t.eq(normCentavos(null), 0);
    t.eq(normCentavos(undefined), 0);
    t.eq(normCentavos('1234'), 1234);
    t.eq(normCentavos(''), 0);
    t.eq(normCentavos(NaN), 0);
    t.eq(normCentavos(12.6), 13);
    t.eq(normCentavos(-7), -7);
  });

  // ------------------------------------------------------------ parsePesos (typed input, like the phone)
  t.check('parsePesos accepts the phone formats', () => {
    t.eq(parsePesos('1,234.50'), 123450);
    t.eq(parsePesos('₱5'), 500);
    t.eq(parsePesos('PHP 5'), 500);
    t.eq(parsePesos('.5'), 50);
    t.eq(parsePesos('0.05'), 5);
    t.eq(parsePesos(' 12 '), 1200);
    t.eq(parsePesos('1,234,567.89'), 123456789);
    t.eq(parsePesos('999999999999'), 99999999999900);
  });
  t.check('parsePesos rejects what the phone rejects', () => {
    for (const bad of ['', ' ', 'abc', '1.234', '12,34', '1,2345', '1..2', '1.2.3', '-5', '5-', '1e3', '9999999999999', '₱', '.', ',5', '1,234,56']) {
      t.eq(parsePesos(bad), null, `"${bad}"`);
    }
    t.eq(parsePesos(null), null);
    t.eq(parsePesos(12), null);
  });
  t.check('parsePesos: a leading minus only when allowed', () => {
    t.eq(parsePesos('-5', { allowNegative: true }), -500);
    t.eq(parsePesos('-1,234.50', { allowNegative: true }), -123450);
  });
  t.check('pesosPlain -> parsePesos round-trips 2000 amounts, formatPesos -> parsePesos too', () => {
    let seed = 12345;
    const next = () => { seed = (seed * 1103515245 + 12345) % 2147483648; return seed; };
    for (let i = 0; i < 2000; i += 1) {
      const c = (next() % 100000) * (i % 7 === 0 ? 100000 : 1) + (next() % 100);
      t.eq(parsePesos(pesosPlain(c)), c, `plain ${c}`);
      t.eq(parsePesos(formatPesos(c)), c, `format ${c}`);
    }
  });

  // ------------------------------------------------------------ csvTotals
  t.check('csvTotals: spent, income, net, vat on expenses', () => {
    const rows = [
      tx({ amount: 10000, vat: 1071 }),
      tx({ amount: 5050, vat: null }),
      tx({ type: 'income', amount: 200000, category: 'salary', vat: 999 }),
    ];
    t.eq(csvTotals(rows), { spent: 15050, income: 200000, net: 184950, vat: 1071 });
  });
  t.check('csvTotals: transfers are excluded from spent, income and net', () => {
    const rows = [tx({ amount: 10000 }), tx({ type: 'transfer', amount: 999999, to_wallet_id: 'w2', category: null })];
    t.eq(csvTotals(rows), { spent: 10000, income: 0, net: -10000, vat: 0 });
  });
  t.check('csvTotals: deleted rows never count', () => {
    const rows = [tx({ amount: 10000 }), tx({ amount: 777, deleted: true }), tx({ type: 'income', amount: 5, deleted: true })];
    t.eq(csvTotals(rows), { spent: 10000, income: 0, net: -10000, vat: 0 });
  });
  t.check('csvTotals: empty list is all zeros; net can be negative', () => {
    t.eq(csvTotals([]), { spent: 0, income: 0, net: 0, vat: 0 });
    t.eq(csvTotals([tx({ amount: 1 })]).net, -1);
  });
  t.check('csvTotals: tolerates numeric strings and null amounts from the API', () => {
    t.eq(csvTotals([tx({ amount: '2500' }), tx({ amount: null })]), { spent: 2500, income: 0, net: -2500, vat: 0 });
  });

  // ------------------------------------------------------------ buildCsv shape
  t.check('buildCsv: BOM first, CRLF everywhere, ends with CRLF, header matches DESIGN.md', () => {
    const csv = buildCsv([tx()], ctx);
    t.eq(csv.charCodeAt(0), 0xfeff);
    t.ok(!/[^\r]\n/.test(csv), 'a bare LF was found');
    t.ok(csv.endsWith('\r\n'));
    t.eq(csv.slice(1).split('\r\n')[0],
      'date,owner,type,wallet,category,merchant,amount,is_business,seller_tin,invoice_no,vatable_sales,vat,vat_exempt,ref_no,note,photo');
  });
  t.check('buildCsv: UTF-8 bytes start EF BB BF and Ñ / ₱ survive an encode and decode', () => {
    const csv = buildCsv([tx({ merchant: 'Señor Ñandú ₱ café', note: 'ÑOÑO' })], ctx);
    const bytes = new TextEncoder().encode(csv);
    t.eq([bytes[0], bytes[1], bytes[2]], [0xef, 0xbb, 0xbf]);
    const back = parseCsv(new TextDecoder('utf-8').decode(bytes));
    t.eq(back[1][col('merchant')], 'Señor Ñandú ₱ café');
    t.eq(back[1][col('note')], 'ÑOÑO');
  });
  t.check('buildCsv: commas, quotes and line breaks are quoted and read back unchanged', () => {
    const nasty = 'Jollibee, "Ayala" branch\nsecond line, with comma';
    const csv = buildCsv([tx({ merchant: nasty, note: '"quoted"' })], ctx);
    const rows = parseCsv(csv);
    t.eq(rows[1][col('merchant')], nasty);
    t.eq(rows[1][col('note')], '"quoted"');
    t.eq(rows[1].length, CSV_COLUMNS.length);
  });
  t.check('csvField quotes only when needed', () => {
    t.eq(csvField('plain'), 'plain');
    t.eq(csvField('a,b'), '"a,b"');
    t.eq(csvField('a"b'), '"a""b"');
    t.eq(csvField('a\nb'), '"a\nb"');
    t.eq(csvField(null), '');
  });
  t.check('buildCsv: formula injection guard on = + - @ tab and CR, normal text untouched', () => {
    for (const evil of ['=HYPERLINK("http://x","y")', '+1+1', '-2+3', '@SUM(A1)', '\t=1', '\r=1', ' =1']) {
      const rows = parseCsv(buildCsv([tx({ merchant: evil, note: evil })], ctx));
      t.ok(rows[1][col('merchant')].startsWith("'"), `merchant ${JSON.stringify(evil)} not guarded`);
      t.ok(rows[1][col('note')].startsWith("'"), `note ${JSON.stringify(evil)} not guarded`);
    }
    t.eq(safeText('Jollibee'), 'Jollibee');
    t.eq(safeText('A-B = C'), 'A-B = C');
    t.eq(safeText(''), '');
    t.eq(safeText(null), '');
  });
  t.check('buildCsv: amounts are pesos with 2 decimals, null money stays empty, 0 stays 0.00', () => {
    const csv = buildCsv([tx({ amount: 12345, vatable_sales: 11274, vat: 1071, vat_exempt: 0 }), tx({ amount: 5 })], ctx);
    const rows = dataRows(csv);
    t.eq(rows.find((r) => r[col('amount')] === '123.45').slice(col('vatable_sales'), col('vat_exempt') + 1), ['112.74', '10.71', '0.00']);
    const second = rows.find((r) => r[col('amount')] === '0.05');
    t.eq(second.slice(col('vatable_sales'), col('vat_exempt') + 1), ['', '', '']);
    t.eq(buildCsv([tx({ amount: 99999999999900 })], ctx).includes('999999999999.00'), true);
  });
  t.check('buildCsv: names for owner and wallet, business yes/no, transfer shows both wallets, photo path', () => {
    const rows = dataRows(buildCsv([
      tx({ owner_id: 'u2', is_business: true, receipt_path: 'u2/abc.jpg', seller_tin: '123-456-789-00000', invoice_no: 'SI-1', ref_no: 'R9' }),
      tx({ type: 'transfer', to_wallet_id: 'w2', category: null, merchant: null, amount: 500 }),
      tx({ owner_id: 'ghost', wallet_id: 'nope' }),
    ], ctx));
    t.eq(rows[0].slice(col('owner'), col('category') + 1), ['Dad', 'expense', 'Cash', 'Food & Dining']);
    t.eq([rows[0][col('is_business')], rows[0][col('seller_tin')], rows[0][col('invoice_no')], rows[0][col('ref_no')], rows[0][col('photo')]],
      ['yes', '123-456-789-00000', 'SI-1', 'R9', 'u2/abc.jpg']);
    t.eq(rows[1][col('wallet')], 'Cash -> GCash');
    t.eq(rows[1][col('is_business')], 'no');
    t.eq([rows[2][col('owner')], rows[2][col('wallet')]], ['', '']);
  });

  // ------------------------------------------------------------ totals rows and filters
  t.check('buildCsv: the totals rows show csvTotals (same numbers the screen shows)', () => {
    const rows = [
      tx({ amount: 10000, vat: 1071 }), tx({ amount: 2550 }),
      tx({ type: 'income', amount: 500000, category: 'salary' }),
      tx({ type: 'transfer', amount: 123456, to_wallet_id: 'w2', category: null }),
      tx({ amount: 99999, deleted: true }),
    ];
    const want = csvTotals(rows);
    const tot = totalRows(buildCsv(rows, ctx));
    t.eq(tot.length, 4);
    const by = Object.fromEntries(tot.map((r) => [r[col('merchant')], r]));
    t.eq(centavos(by['Total spent'][col('amount')]), want.spent);
    t.eq(centavos(by['Total income'][col('amount')]), want.income);
    t.eq(centavos(by['Net (income minus spent)'][col('amount')]), want.net);
    t.eq(centavos(by['VAT on expenses'][col('vat')]), want.vat);
    t.eq(want, { spent: 12550, income: 500000, net: 487450, vat: 1071 });
  });
  t.check('buildCsv: the expense rows add up to the Total spent row', () => {
    const rows = [tx({ amount: 1 }), tx({ amount: 2 }), tx({ amount: 3333 }), tx({ type: 'income', amount: 7, category: 'gift' })];
    const csv = buildCsv(rows, ctx);
    const sum = dataRows(csv).filter((r) => r[col('type')] === 'expense').reduce((s, r) => s + centavos(r[col('amount')]), 0);
    t.eq(sum, centavos(totalRows(csv)[0][col('amount')]));
  });
  t.check('buildCsv: deleted rows are not listed and not counted', () => {
    const csv = buildCsv([tx({ merchant: 'Kept' }), tx({ merchant: 'Gone', deleted: true })], ctx);
    t.ok(csv.includes('Kept'));
    t.ok(!csv.includes('Gone'));
    t.eq(dataRows(csv).length, 1);
  });
  t.check('buildCsv: transfers are listed but excluded from the totals rows', () => {
    const csv = buildCsv([tx({ type: 'transfer', amount: 55555, to_wallet_id: 'w2', category: null })], ctx);
    t.eq(dataRows(csv).length, 1);
    t.eq(centavos(totalRows(csv)[0][col('amount')]), 0);
    t.eq(centavos(totalRows(csv)[1][col('amount')]), 0);
  });
  t.check('buildCsv: a business-only filter is reflected in rows and totals', () => {
    const all = [tx({ merchant: 'Biz', amount: 4000, is_business: true }), tx({ merchant: 'Home', amount: 9000 })];
    const filtered = applyFilters(all, { business: true });
    const csv = buildCsv(filtered, ctx);
    t.eq(dataRows(csv).map((r) => r[col('merchant')]), ['Biz']);
    t.eq(centavos(totalRows(csv)[0][col('amount')]), 4000);
    t.ok(!csv.includes('Home'));
  });
  t.check('buildCsv: an empty list still gives BOM, header and four zero totals rows', () => {
    const csv = buildCsv([], ctx);
    const rows = parseCsv(csv);
    t.eq(csv.charCodeAt(0), 0xfeff);
    t.eq(rows.length, 5);
    t.eq(totalRows(csv).map((r) => r[col('amount')] || r[col('vat')]), ['0.00', '0.00', '0.00', '0.00']);
    t.eq(buildCsv(undefined, undefined).length > 0, true);
  });
}
