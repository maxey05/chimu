// Run the same checks as test.html from a terminal:  node web/test-node.mjs
import { makeT, summary } from './check.js';

const modules = ['./test-csv.js', './test-core.js', './test-stats.js'];
const t = makeT();
for (const name of modules) {
  const m = await import(name);
  m.tests(t);
}
await t.settle();
const { total, passed, failed } = summary(t.results);
for (const r of t.results) console.log(`${r.ok ? 'PASS' : 'FAIL'}  ${r.name}${r.ok ? '' : '\n      ' + r.error}`);
console.log(`\n${passed}/${total} passed`);
process.exit(failed.length ? 1 : 0);
