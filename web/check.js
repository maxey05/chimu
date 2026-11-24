// Tiny test harness shared by test.html (browser) and test-node.mjs (Node). No DOM, no imports.
// Each test module exports `tests(t)` and registers checks with t.check(name, fn).
export function makeT() {
  const results = [];
  const pending = [];
  const fail = (message) => { throw new Error(message); };
  return {
    results,
    check(name, fn) {
      try { fn(); results.push({ name, ok: true }); }
      catch (e) { results.push({ name, ok: false, error: String((e && e.message) || e) }); }
    },
    // Async check: registered now, run by settle(). Its result keeps its place in the list.
    later(name, fn) {
      const slot = { name, ok: false, error: 'did not finish' };
      results.push(slot);
      pending.push(Promise.resolve().then(fn).then(
        () => { slot.ok = true; delete slot.error; },
        (e) => { slot.ok = false; slot.error = String((e && e.message) || e); },
      ));
    },
    // Wait for every later() check. Call once after all tests(t) calls, before summary().
    settle() { return Promise.all(pending); },
    eq(actual, expected, message = '') {
      const a = JSON.stringify(actual);
      const b = JSON.stringify(expected);
      if (a !== b) fail(`${message} expected ${b} but got ${a}`.trim());
    },
    ok(value, message = 'expected a truthy value') { if (!value) fail(message); },
  };
}

export function summary(results) {
  const failed = results.filter((r) => !r.ok);
  return { total: results.length, passed: results.length - failed.length, failed };
}
