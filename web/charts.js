// Hand-drawn SVG charts for the dashboard (RFC-008, RULES 4: no chart library).
// Every builder returns a DOM element and never touches innerHTML: names, categories and amounts are
// untrusted data and only ever go in through textContent / setAttribute.
//
// Look and behaviour follow the dataviz skill: thin marks (24px cap, 4px rounded data end, square at
// the baseline), a 2px surface gap between stacked segments, hairline recessive grid, direct labels
// used sparingly, text in ink colors (never the series color), one tooltip per mark that shows on
// hover AND keyboard focus, and a table twin for every chart (dataTable). Colors come from CSS custom
// properties in dashboard.css so light and dark are chosen in one place.
import { formatPesos } from './money.js';

const SVG_NS = 'http://www.w3.org/2000/svg';
// Charts are drawn in a fixed box and scaled to their card by CSS, so a unit is about one pixel in the
// card it is meant for: COL_W for the full-width column charts, ROW_W for the half-width bar lists.
const COL_W = 900;
const ROW_W = 600;
const BAR_MAX = 24; // the thickest a bar or column may be
const GAP = 2; // surface gap between touching segments
const RADIUS = 4; // rounded data end
const TEXT_W = 7.1; // rough width of one 12-13px character, for fitting labels

// --- tiny DOM helpers ------------------------------------------------------------------------------

export function el(tag, attrs = {}, children = []) {
  const node = document.createElement(tag);
  for (const [k, v] of Object.entries(attrs)) if (v !== null && v !== undefined) node.setAttribute(k, v);
  for (const c of [].concat(children)) node.append(c);
  return node;
}

function svgEl(tag, attrs = {}, children = []) {
  const node = document.createElementNS(SVG_NS, tag);
  for (const [k, v] of Object.entries(attrs)) if (v !== null && v !== undefined) node.setAttribute(k, v);
  for (const c of [].concat(children)) node.append(c);
  return node;
}

const round1 = (n) => Math.round(n * 10) / 10;

// --- scale -----------------------------------------------------------------------------------------

// A "nice" top and tick step for a value axis, in whole pesos (so labels never need decimals).
// maxCentavos <= 0 gives a P1 axis. Returns { top, ticks } in centavos, ticks from 0 to top.
export function niceScale(maxCentavos) {
  const maxPesos = Math.max(maxCentavos, 0) / 100;
  const raw = Math.max(maxPesos, 1) / 4;
  const mag = 10 ** Math.floor(Math.log10(raw));
  const step = Math.max(1, [1, 2, 5, 10].map((m) => m * mag).find((s) => s >= raw));
  const count = Math.max(1, Math.ceil(maxPesos / step));
  const ticks = Array.from({ length: count + 1 }, (_, i) => Math.round(i * step * 100));
  return { top: ticks[ticks.length - 1], ticks };
}

// An axis tick: whole pesos, no decimals ("₱5,000").
function tickLabel(centavos) {
  return formatPesos(centavos).replace(/\.00$/, '');
}

// The word for a share: 34.5%.
export function percentText(part, whole) {
  return whole > 0 ? `${(Math.round((part * 1000) / whole) / 10).toFixed(1)}%` : '';
}

// A bar with a square base and a rounded top (a column). Heights under the radius shrink the radius.
function columnPath(x, y, w, h) {
  const r = Math.min(RADIUS, h, w / 2);
  return `M${x},${y + h}V${y + r}Q${x},${y} ${x + r},${y}H${x + w - r}Q${x + w},${y} ${x + w},${y + r}V${y + h}Z`;
}

// A bar with a square base on the left and a rounded right end.
function rowPath(x, y, w, h) {
  const r = Math.min(RADIUS, w, h / 2);
  return `M${x},${y}H${x + w - r}Q${x + w},${y} ${x + w},${y + r}V${y + h - r}Q${x + w},${y + h} ${x + w - r},${y + h}H${x}Z`;
}

// --- tooltip ---------------------------------------------------------------------------------------

// One tooltip per chart. It is positioned from the mark's box, so mouse and keyboard focus get the
// same readout. It is aria-hidden: screen readers get the same words from each mark's aria-label.
function makeTooltip(plot) {
  const tip = el('div', { class: 'dash-tip', 'aria-hidden': 'true', hidden: '' });
  plot.append(tip);

  function show(mark, data) {
    tip.replaceChildren();
    tip.append(el('div', { class: 'dash-tip-title' }, data.title));
    for (const row of data.rows) {
      const line = el('div', { class: 'dash-tip-row' });
      if (row.slot) line.append(el('span', { class: `dash-key dash-s${row.slot}` }));
      line.append(el('strong', {}, row.value));
      line.append(el('span', { class: 'dash-tip-label' }, row.label));
      tip.append(line);
    }
    tip.hidden = false;
    const p = plot.getBoundingClientRect();
    const m = mark.getBoundingClientRect();
    const tw = tip.offsetWidth;
    const th = tip.offsetHeight;
    const left = Math.min(Math.max(m.left - p.left + m.width / 2 - tw / 2, 0), Math.max(p.width - tw, 0));
    const above = m.top - p.top - th - 8;
    tip.style.left = `${Math.round(left)}px`;
    tip.style.top = `${Math.round(above >= 0 ? above : m.bottom - p.top + 8)}px`;
  }

  return { show, hide: () => { tip.hidden = true; } };
}

// Makes `mark` focusable and wires hover + focus to the tooltip. `data` = { title, rows: [{ value,
// label, slot? }] }. The same words become the native <title> and the aria-label.
function makeMark(tooltip, data, children) {
  const plain = `${data.title}: ${data.rows.map((r) => `${r.value} ${r.label}`).join(', ')}`;
  const mark = svgEl('g', { class: 'dash-mark', tabindex: '0', role: 'img', 'aria-label': plain });
  mark.append(svgEl('title', {}, plain));
  for (const child of children) mark.append(child);
  const show = () => tooltip.show(mark, data);
  mark.addEventListener('mouseenter', show);
  mark.addEventListener('focus', show);
  mark.addEventListener('mouseleave', tooltip.hide);
  mark.addEventListener('blur', tooltip.hide);
  mark.addEventListener('keydown', (e) => { if (e.key === 'Escape') tooltip.hide(); });
  return mark;
}

function makePlot(svg) {
  const plot = el('div', { class: 'dash-plot' });
  plot.append(svg);
  return { plot, tooltip: makeTooltip(plot) };
}

// --- shared axes for the two column charts ---------------------------------------------------------

const H = 280;
const PAD_TOP = 18;
const PAD_BOTTOM = 36; // room for the month names and the selection marker

// Draws grid, value axis and month names; returns the geometry the bars need.
function drawColumnAxes(svg, categories, top, ticks, highlight) {
  const labels = ticks.map(tickLabel);
  const left = Math.ceil(14 + TEXT_W * Math.max(...labels.map((l) => l.length)));
  const right = 10;
  const plotBottom = H - PAD_BOTTOM;
  const plotH = plotBottom - PAD_TOP;
  const band = (COL_W - left - right) / categories.length;
  const y = (v) => plotBottom - (v / top) * plotH;

  ticks.forEach((t, i) => {
    svg.append(svgEl('line', {
      class: i === 0 ? 'dash-axis' : 'dash-gridline', x1: left, x2: COL_W - right, y1: y(t), y2: y(t),
    }));
    svg.append(svgEl('text', { class: 'dash-tick', x: left - 8, y: y(t), 'text-anchor': 'end', dy: '0.32em' }, labels[i]));
  });
  categories.forEach((c, i) => {
    const cx = left + band * (i + 0.5);
    const selected = i === highlight;
    svg.append(svgEl('text', {
      class: selected ? 'dash-tick dash-tick-on' : 'dash-tick', x: cx, y: plotBottom + 17, 'text-anchor': 'middle',
    }, c.short));
    if (selected) {
      svg.append(svgEl('rect', { class: 'dash-selected', x: cx - 10, y: plotBottom + 23, width: 20, height: 3, rx: 1.5 }));
    }
  });
  return { left, right, plotBottom, plotH, band, y, barW: Math.min(BAR_MAX, band * 0.6) };
}

function columnSvg(label) {
  return svgEl('svg', { class: 'dash-svg', viewBox: `0 0 ${COL_W} ${H}`, role: 'group', 'aria-label': label, focusable: 'false' });
}

// --- column chart: one series (monthly spend) ------------------------------------------------------

// items: [{ short: 'Jan', name: 'January 2026', value }] in centavos. highlight: index of the month
// to emphasise (the others turn gray), or -1 for none. valueLabel: 'spent'.
// Only the tallest column and the highlighted one carry a printed value; the tooltip and the table
// carry the rest.
export function columnChart({ label, items, highlight = -1, valueLabel = 'spent' }) {
  const max = Math.max(...items.map((i) => i.value), 0);
  const { top, ticks } = niceScale(max);
  const svg = columnSvg(label);
  const g = drawColumnAxes(svg, items, top, ticks, highlight);
  const { plot, tooltip } = makePlot(svg);

  items.forEach((item, i) => {
    const cx = g.left + g.band * (i + 0.5);
    const h = (item.value / top) * g.plotH;
    const parts = [svgEl('rect', { class: 'dash-hit', x: cx - g.band / 2, y: PAD_TOP, width: g.band, height: g.plotH })];
    if (item.value > 0) {
      const dim = highlight >= 0 && i !== highlight;
      parts.push(svgEl('path', { class: `dash-bar ${dim ? 'dash-dim' : 'dash-s1'}`, d: columnPath(cx - g.barW / 2, g.plotBottom - h, g.barW, h) }));
    }
    svg.append(makeMark(tooltip, { title: item.name, rows: [{ value: formatPesos(item.value), label: valueLabel, slot: 1 }] }, parts));
  });

  // Printed values: the highlighted column first, then the tallest if it does not collide.
  const tallest = max > 0 ? items.findIndex((i) => i.value === max) : -1;
  const wanted = [highlight, tallest].filter((i, n, all) => i >= 0 && items[i].value > 0 && all.indexOf(i) === n);
  const placed = [];
  for (const i of wanted) {
    const text = formatPesos(items[i].value);
    const half = (text.length * TEXT_W) / 2;
    const cx = Math.min(Math.max(g.left + g.band * (i + 0.5), g.left + half), COL_W - g.right - half);
    if (placed.some((p) => Math.abs(p.cx - cx) < p.half + half + 6)) continue;
    placed.push({ cx, half });
    svg.append(svgEl('text', {
      class: 'dash-value', x: round1(cx), y: round1(g.plotBottom - (items[i].value / top) * g.plotH - 6), 'text-anchor': 'middle',
    }, text));
  }
  return plot;
}

// --- stacked column chart: one series per member ---------------------------------------------------

// categories: [{ short, name }] (12 months). series: [{ name, slot, tag, values: [per category] }],
// slot is 1-8 (a categorical color) or 'other' (gray); tag is the short mark (a number) printed inside
// segments that are big enough, so identity never depends on color alone.
export function stackedColumnChart({ label, categories, series, highlight = -1, valueLabel = 'spent' }) {
  const totals = categories.map((_, i) => series.reduce((sum, s) => sum + s.values[i], 0));
  const { top, ticks } = niceScale(Math.max(...totals, 0));
  const svg = columnSvg(label);
  const g = drawColumnAxes(svg, categories, top, ticks, highlight);
  const { plot, tooltip } = makePlot(svg);

  categories.forEach((cat, i) => {
    const cx = g.left + g.band * (i + 0.5);
    const x = cx - g.barW / 2;
    const parts = [svgEl('rect', { class: 'dash-hit', x: cx - g.band / 2, y: PAD_TOP, width: g.band, height: g.plotH })];
    const live = series.filter((s) => s.values[i] > 0);
    let floor = g.plotBottom; // the bottom edge of the next segment
    live.forEach((s, n) => {
      const h = (s.values[i] / top) * g.plotH;
      const isTop = n === live.length - 1;
      const drawn = Math.max(h - (isTop ? 0 : GAP), 1); // the 2px gap sits above every segment but the last
      const y = floor - drawn;
      parts.push(svgEl('path', {
        class: `dash-bar dash-s${s.slot}`,
        d: isTop ? columnPath(x, y, g.barW, drawn) : `M${x},${y}h${g.barW}v${drawn}h${-g.barW}Z`,
      }));
      if (drawn >= 15 && g.barW >= 15) {
        parts.push(svgEl('text', {
          class: `dash-segtag dash-on-s${s.slot}`, x: cx, y: y + drawn / 2, 'text-anchor': 'middle', dy: '0.35em',
        }, s.tag));
      }
      floor -= h;
    });
    const rows = live.map((s) => ({ value: formatPesos(s.values[i]), label: s.name, slot: s.slot })).reverse();
    rows.unshift({ value: formatPesos(totals[i]), label: 'all members' });
    const data = live.length
      ? { title: cat.name, rows }
      : { title: cat.name, rows: [{ value: formatPesos(0), label: valueLabel }] };
    svg.append(makeMark(tooltip, data, parts));
  });
  return plot;
}

// --- horizontal bars: one row per category or member -----------------------------------------------

const ROW_PITCH = 32;
const ROW_BAR = 14;
const NAME_W = 170; // where the bars start
const VALUE_W = 150; // room after the longest bar for its value and share
const NAME_CHARS = 20;

function shorten(text) {
  const chars = [...text];
  return chars.length > NAME_CHARS ? `${chars.slice(0, NAME_CHARS - 1).join('')}…` : text;
}

// rows: [{ name, value, slot?, tag?, detail? }] (value in centavos, largest first). Names are on the
// left, the value (and optionally a share) at the bar tip. A row with a tag gets a small colored
// chip carrying it, matching the legend.
export function barList({ label, rows, valueLabel = 'spent' }) {
  const height = ROW_PITCH * rows.length + 8;
  const svg = svgEl('svg', { class: 'dash-svg', viewBox: `0 0 ${ROW_W} ${height}`, role: 'group', 'aria-label': label, focusable: 'false' });
  const { plot, tooltip } = makePlot(svg);
  const max = Math.max(...rows.map((r) => r.value), 1);
  const barRoom = ROW_W - NAME_W - VALUE_W;
  svg.append(svgEl('line', { class: 'dash-axis', x1: NAME_W, x2: NAME_W, y1: 2, y2: height - 2 }));

  rows.forEach((row, i) => {
    const y = 4 + i * ROW_PITCH;
    const mid = y + ROW_PITCH / 2;
    const slot = row.slot || 1;
    const parts = [svgEl('rect', { class: 'dash-hit', x: 0, y, width: ROW_W, height: ROW_PITCH })];
    if (row.tag) {
      parts.push(svgEl('rect', { class: `dash-chip-rect dash-s${slot}`, x: 0, y: mid - 9, width: 18, height: 18, rx: 4 }));
      parts.push(svgEl('text', { class: `dash-segtag dash-on-s${slot}`, x: 9, y: mid, 'text-anchor': 'middle', dy: '0.35em' }, row.tag));
    }
    parts.push(svgEl('text', { class: 'dash-name', x: row.tag ? 26 : 0, y: mid, dy: '0.35em' }, shorten(row.name)));
    const w = row.value > 0 ? Math.max((row.value / max) * barRoom, 2) : 0;
    if (w > 0) parts.push(svgEl('path', { class: `dash-bar dash-s${slot}`, d: rowPath(NAME_W, mid - ROW_BAR / 2, w, ROW_BAR) }));
    parts.push(svgEl('text', { class: 'dash-value', x: NAME_W + w + 8, y: mid, dy: '0.35em' }, formatPesos(row.value)));
    if (row.detail) {
      const used = formatPesos(row.value).length * TEXT_W + 8;
      parts.push(svgEl('text', { class: 'dash-detail', x: NAME_W + w + 8 + used + 6, y: mid, dy: '0.35em' }, row.detail));
    }
    const tipRows = [{ value: formatPesos(row.value), label: valueLabel, slot }];
    if (row.detail) tipRows.push({ value: row.detail, label: 'of the total' });
    svg.append(makeMark(tooltip, { title: row.name, rows: tipRows }, parts));
  });
  return plot;
}

// --- budget progress bar ---------------------------------------------------------------------------

const PROGRESS_W = 600;
const PROGRESS_H = 16;
const PROGRESS_BAR = 12;

// One budget's progress: a track, a fill and a tick at the 80% warning mark. fraction is 0..1 and is
// clamped here too, so a budget that is 125% used fills the track and stops. tone is 'ok', 'warn' or
// 'over' and only picks the fill's color (dashboard.css); the words next to the bar carry the meaning,
// so the bar itself is hidden from screen readers.
export function progressBar({ fraction, tone, warnAt = 0.8 }) {
  const clamped = Math.min(1, Math.max(0, Number.isFinite(fraction) ? fraction : 0));
  const y = (PROGRESS_H - PROGRESS_BAR) / 2;
  const svg = svgEl('svg', {
    class: 'dash-progress', viewBox: `0 0 ${PROGRESS_W} ${PROGRESS_H}`, 'aria-hidden': 'true', focusable: 'false',
  });
  svg.append(svgEl('rect', { class: 'dash-track', x: 0, y, width: PROGRESS_W, height: PROGRESS_BAR, rx: RADIUS }));
  if (clamped > 0) {
    const w = Math.max(round1(clamped * PROGRESS_W), 2);
    svg.append(svgEl('path', { class: `dash-bar dash-lvl-${tone}`, d: rowPath(0, y, w, PROGRESS_BAR) }));
  }
  if (warnAt !== null) { // a goal has no warning mark (RFC-011): it passes warnAt: null
    const x = round1(warnAt * PROGRESS_W);
    svg.append(svgEl('line', { class: 'dash-progress-tick', x1: x, x2: x, y1: 0, y2: PROGRESS_H }));
  }
  return svg;
}

// --- legend and table twin -------------------------------------------------------------------------

// series: [{ name, slot, tag }]. A numbered chip plus the name: identity by mark and word, not by color.
export function legend(series) {
  const list = el('ul', { class: 'dash-legend' });
  for (const s of series) {
    list.append(el('li', {}, [el('span', { class: `dash-chip dash-s${s.slot}`, 'aria-hidden': 'true' }, s.tag), el('span', {}, s.name)]));
  }
  return list;
}

// A table of text cells. numeric: which columns are numbers (right-aligned). The first column is the row header.
export function table({ caption, headers, rows, numeric = [] }) {
  const head = el('tr', {}, headers.map((h, i) => el('th', { scope: 'col', class: numeric[i] ? 'num' : null }, h)));
  const body = rows.map((cells) => el('tr', {}, cells.map((c, i) => (
    i === 0 ? el('th', { scope: 'row' }, c) : el('td', { class: numeric[i] ? 'num' : null }, c)
  ))));
  const node = el('table', { class: 'dash-table' }, [el('caption', {}, caption), el('thead', {}, head), el('tbody', {}, body)]);
  return el('div', { class: 'dash-tablewrap' }, node);
}

// A collapsed "View as table" twin of a chart: the same numbers, reachable without a pointer or color.
export function dataTable(spec) {
  return el('details', { class: 'dash-details' }, [el('summary', {}, 'View as table'), table(spec)]);
}
