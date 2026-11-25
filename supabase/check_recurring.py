#!/usr/bin/env python3
"""Check Chimu's recurring-rule generator (RFC-010, supabase/migrations/0003_recurring.sql).

Python 3 standard library only. It talks to Postgres through the `psql` command line client, because
generate_recurring() cannot be called through the API on purpose (only the postgres role may run it, see the
migration), and the service-role key must never be used from a script (RULES 13). So it connects directly:

    $env:SUPABASE_DB_URL = "postgresql://postgres:<db password>@db.<ref>.supabase.co:5432/postgres"
    python supabase/check_recurring.py

(Supabase dashboard -> Project Settings -> Database -> Connection string. Use the direct or the session pooler
string, not the transaction pooler: this script keeps one session open. If SUPABASE_DB_URL is not set, psql
uses the usual PGHOST / PGUSER / PGPASSWORD / PGDATABASE variables.)

Nothing is left behind. Everything runs inside ONE transaction that is rolled back at the end: the two test
users, their wallets and rules, and every transaction the generator makes disappear. The `rev` sequence still
moves forward (sequences are not rolled back), which is harmless. The check passes `as_of` dates to
generate_recurring() to act out other days, except for the one test that runs it the way the cron job does.

What it asserts: the date arithmetic (a table of cases shared with RecurringTest.kt, plus every day of five
years against an independent Python version), a rule due today appears, a second run adds nothing, month-end
clamping and the anchor day, catch-up after three missed months, paused / deleted / future rules are ignored,
a deleted generated row stays deleted, the generated rows are proper synced rows (owner, epoch-ms updated_at,
rev above the pull cursor, visible to their owner only), one failing rule does not stop the others, the
per-rule cap, who may call the function, and that the cron job is scheduled for 00:10 Asia/Manila.

Exit code 0 = every assertion passed, 1 = at least one failed.
"""
import calendar
import datetime
import os
import subprocess
import sys
import time
import uuid

results = []  # (ok, name)


def check(name, ok, detail=""):
    results.append((bool(ok), name))
    print(f"  {'PASS' if ok else 'FAIL'}  {name}" + (f"  [{detail}]" if detail and not ok else ""))
    return bool(ok)


# ---------------------------------------------------------------------------------------------------------
# One long-lived psql session
# ---------------------------------------------------------------------------------------------------------
END = "__chimu_end__"
NOISE = ("ERROR:", "WARNING:", "NOTICE:", "DETAIL:", "HINT:", "CONTEXT:", "LINE ", "QUERY:", "STATEMENT:")


class Result:
    def __init__(self, rows, messages):
        self.rows = rows            # list of lists of strings (psql -A -t -F '|')
        self.messages = messages    # ERROR / WARNING / NOTICE lines, in order

    @property
    def errors(self):
        return [m for m in self.messages if m.startswith("ERROR:")]

    @property
    def warnings(self):
        return [m for m in self.messages if m.startswith("WARNING:")]

    @property
    def ok(self):
        return not self.errors

    def cell(self):
        return self.rows[0][0] if self.rows and self.rows[0] else None


class Psql:
    def __init__(self, dsn):
        cmd = ["psql", "-X", "-q", "-A", "-t", "-F", "|", "-v", "ON_ERROR_STOP=0"]
        if dsn:
            cmd.append(dsn)
        try:
            self.proc = subprocess.Popen(cmd, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                         stderr=subprocess.STDOUT, text=True, bufsize=1)
        except FileNotFoundError:
            sys.exit("psql was not found. Install the PostgreSQL command line tools and put psql on PATH.")

    def run(self, sql):
        self.proc.stdin.write(sql.strip() + "\n\\echo " + END + "\n")
        self.proc.stdin.flush()
        rows, messages = [], []
        while True:
            line = self.proc.stdout.readline()
            if line == "":
                sys.exit("psql closed unexpectedly. Output so far: " + " | ".join(messages[-5:]))
            line = line.rstrip("\n")
            if line == END:
                return Result(rows, messages)
            if line.startswith(NOISE) or line.startswith("psql:"):
                messages.append(line)
            elif line:
                rows.append(line.split("|"))

    def close(self):
        try:
            self.proc.stdin.write("rollback;\n\\q\n")
            self.proc.stdin.flush()
        except (BrokenPipeError, ValueError):
            pass
        self.proc.wait(timeout=30)


# ---------------------------------------------------------------------------------------------------------
# Independent date arithmetic (not a transcription of the SQL: it uses the calendar module)
# ---------------------------------------------------------------------------------------------------------
def py_next(freq, d, anchor):
    if freq == "weekly":
        return d + datetime.timedelta(days=7)
    want = anchor if anchor is not None else d.day
    year, month = d.year, d.month
    if freq == "monthly":
        year, month = (year + 1, 1) if month == 12 else (year, month + 1)
    else:
        year += 1
    return datetime.date(year, month, min(want, calendar.monthrange(year, month)[1]))


# The shared test vector: (frequency, from, anchor day or None, expected next date).
# RecurringTest.kt holds the same table; change one, change both.
VECTOR = [
    ("monthly", "2026-01-31", None, "2026-02-28"),
    ("monthly", "2026-02-28", 31, "2026-03-31"),
    ("monthly", "2026-02-28", None, "2026-03-28"),
    ("monthly", "2028-01-31", None, "2028-02-29"),
    ("monthly", "2028-02-29", None, "2028-03-29"),
    ("monthly", "2026-03-31", None, "2026-04-30"),
    ("monthly", "2026-04-30", 31, "2026-05-31"),
    ("monthly", "2026-01-15", 31, "2026-02-28"),
    ("monthly", "2026-12-15", None, "2027-01-15"),
    ("monthly", "2026-12-31", None, "2027-01-31"),
    ("monthly", "2026-11-30", 30, "2026-12-30"),
    ("yearly", "2024-02-29", None, "2025-02-28"),
    ("yearly", "2025-02-28", 29, "2026-02-28"),
    ("yearly", "2027-02-28", 29, "2028-02-29"),
    ("yearly", "2026-06-15", None, "2027-06-15"),
    ("weekly", "2026-11-23", None, "2026-11-30"),
    ("weekly", "2026-12-28", None, "2027-01-04"),
    ("weekly", "2026-01-31", 15, "2026-02-07"),
]


def sql_lit(v):
    return "null" if v is None else str(v)


def run(db):
    # -----------------------------------------------------------------------------------------------
    print("setup")
    a_id, b_id = str(uuid.uuid4()), str(uuid.uuid4())
    wa, wb = str(uuid.uuid4()), str(uuid.uuid4())
    r = db.run("begin; set datestyle = 'ISO, YMD';")
    if not check("transaction opened", r.ok, r.messages):
        return
    r = db.run(f"""
        insert into auth.users (id, email) values
          ('{a_id}', 'chimu-recurring-a-{a_id[:8]}@example.com'),
          ('{b_id}', 'chimu-recurring-b-{b_id[:8]}@example.com');
        insert into public.wallets (id, owner_id, updated_at, name, type) values
          ('{wa}', '{a_id}', 1, 'Check wallet A', 'cash'),
          ('{wb}', '{b_id}', 1, 'Check wallet B', 'cash');""")
    if not check("two test users and wallets created", r.ok, r.messages):
        return

    def add_rule(owner, wallet, freq, next_date, amount=150000, anchor=None, active=True, deleted=False,
                 merchant="Check Landlord", category="bills", rtype="expense"):
        rid = str(uuid.uuid4())
        res = db.run(f"""
            insert into public.recurring
              (id, owner_id, updated_at, type, wallet_id, amount, category, merchant, note, frequency,
               next_date, remind_days_before, active, deleted, anchor_day)
            values ('{rid}', '{owner}', 1000, '{rtype}', '{wallet}', {amount}, '{category}', '{merchant}',
                    'check note', '{freq}', '{next_date}', 1, {str(active).lower()}, {str(deleted).lower()},
                    {sql_lit(anchor)});""")
        if not res.ok:
            sys.exit(f"could not create a rule: {res.messages}")
        return rid

    def dates_of(rule_id, include_deleted=True):
        where = "" if include_deleted else " and not deleted"
        res = db.run(f"select coalesce(string_agg(date::text, ',' order by date), '') "
                     f"from public.transactions where recurring_id = '{rule_id}'{where};")
        return res.cell() or ""

    def count_of(rule_id):
        return int(db.run(f"select count(*) from public.transactions where recurring_id = '{rule_id}';").cell() or -1)

    def next_of(rule_id):
        return db.run(f"select next_date::text from public.recurring where id = '{rule_id}';").cell()

    def generate(as_of=None, only=None):
        """Runs generate_recurring(). `only` (rule ids) pauses the other test rules for the run, so a scenario set in
        2028 does not drag the rules of the scenarios before it along; they are resumed straight after."""
        arg = f"'{as_of}'" if as_of else ""
        paused_here = []
        if only is not None:
            keep = ", ".join(f"'{i}'" for i in only)
            paused_here = [row[0] for row in db.run(
                f"select id::text from public.recurring where owner_id in ('{a_id}', '{b_id}') "
                f"and active and id not in ({keep});").rows]
            for rid in paused_here:
                db.run(f"update public.recurring set active = false where id = '{rid}';")
        res = db.run(f"select public.generate_recurring({arg});")
        for rid in paused_here:
            db.run(f"update public.recurring set active = true where id = '{rid}';")
        return res

    # -----------------------------------------------------------------------------------------------
    print("what is installed")
    r = db.run("select count(*) from pg_proc where proname in ('generate_recurring', 'recurring_next_date') "
               "and pronamespace = 'public'::regnamespace;")
    check("generate_recurring() and recurring_next_date() exist", r.cell() == "2", r.messages)
    r = db.run("select data_type from information_schema.columns where table_schema = 'public' "
               "and table_name = 'recurring' and column_name = 'anchor_day';")
    check("recurring.anchor_day exists", r.cell() == "smallint", r.rows)
    r = db.run("select schedule, command from cron.job where jobname = 'chimu-generate-recurring';")
    check("cron job is scheduled for 16:10 UTC = 00:10 Asia/Manila",
          r.rows and r.rows[0][0] == "10 16 * * *", r.rows)
    check("cron job runs generate_recurring() with no arguments",
          r.rows and "generate_recurring()" in "|".join(r.rows[0][1:]), r.rows)
    r = db.run("select count(*) from cron.job where jobname = 'chimu-generate-recurring';")
    check("exactly one such cron job", r.cell() == "1", r.rows)

    # -----------------------------------------------------------------------------------------------
    print("who may call it")
    r = db.run("select has_function_privilege('anon', 'public.generate_recurring(date)', 'execute'), "
               "has_function_privilege('authenticated', 'public.generate_recurring(date)', 'execute'), "
               "has_function_privilege('postgres', 'public.generate_recurring(date)', 'execute'), "
               "has_function_privilege('anon', 'public.recurring_next_date(text, date, int)', 'execute'), "
               "has_function_privilege('authenticated', 'public.recurring_next_date(text, date, int)', 'execute');")
    check("anon and authenticated hold no execute privilege, postgres does",
          r.rows == [["f", "f", "t", "f", "f"]], r.rows)
    for role in ("anon", "authenticated"):
        r = db.run(f"savepoint s; set local role {role}; select public.generate_recurring(); rollback to savepoint s;")
        check(f"calling generate_recurring() as {role} is refused",
              any("permission denied" in e for e in r.errors), r.messages)
        r = db.run(f"savepoint s; set local role {role}; select public.generate_recurring(date '2099-01-01'); "
                   "rollback to savepoint s;")
        check(f"... also with an explicit date, as {role}",
              any("permission denied" in e for e in r.errors), r.messages)

    # -----------------------------------------------------------------------------------------------
    print("date arithmetic")
    values = ", ".join(f"('{f}', date '{d}', {sql_lit(an)}::int, date '{exp}')" for f, d, an, exp in VECTOR)
    r = db.run(f"select count(*) filter (where public.recurring_next_date(f, d, a) = e), count(*) "
               f"from (values {values}) v(f, d, a, e);")
    check(f"the shared test vector: {len(VECTOR)} cases", r.rows == [[str(len(VECTOR)), str(len(VECTOR))]], r.rows)
    bad = [(f, d, an, exp) for f, d, an, exp in VECTOR
           if py_next(f, datetime.date.fromisoformat(d), an) != datetime.date.fromisoformat(exp)]
    check("... and the vector agrees with the independent Python version", not bad, bad)

    r = db.run("""
        select f, d::text, coalesce(a::text, ''), public.recurring_next_date(f, d, a)::text
        from unnest(array['weekly', 'monthly', 'yearly']) f
        cross join generate_series(date '2024-01-01', date '2028-12-31', interval '1 day') g(d0)
        cross join lateral (select d0::date d) x
        cross join unnest(array[null, 1, 15, 28, 29, 30, 31]::int[]) a;""")
    mism, total = [], 0
    for f, d, a, got in r.rows:
        total += 1
        exp = py_next(f, datetime.date.fromisoformat(d), int(a) if a else None).isoformat()
        if exp != got:
            mism.append((f, d, a, got, exp))
    check(f"every day of 2024-2028 x 3 frequencies x 7 anchors matches Python ({total} cases)",
          total == 1827 * 21 and not mism, mism[:3] or r.messages or total)
    r = db.run("savepoint s; select public.recurring_next_date('daily', date '2026-01-01'); rollback to savepoint s;")
    check("an unknown frequency is an error, not a guess", bool(r.errors), r.messages)

    # -----------------------------------------------------------------------------------------------
    print("a rule due today appears; a second run adds nothing")
    today = db.run("select (now() at time zone 'Asia/Manila')::date::text;").cell()
    today_d = datetime.date.fromisoformat(today)
    cursor_before = int(db.run("select coalesce(max(rev), 0) from public.transactions;").cell())
    rule_cursor_before = int(db.run("select coalesce(max(rev), 0) from public.recurring;").cell())
    r1 = add_rule(a_id, wa, "monthly", today, amount=123456, merchant="Due Today Landlord")
    future = add_rule(a_id, wa, "monthly", (today_d + datetime.timedelta(days=1)).isoformat(), merchant="Tomorrow")
    t0 = int(time.time() * 1000)
    res = generate()  # exactly how the cron job calls it
    check("select generate_recurring() runs without errors", res.ok, res.messages)
    t1 = int(time.time() * 1000)
    check("the rule due today produced a transaction", count_of(r1) == 1, count_of(r1))
    check("the rule due tomorrow produced nothing", count_of(future) == 0)
    row = db.run(f"""select id::text, owner_id::text, type, wallet_id::text, amount, date::text, category, merchant, note,
                            source, recurring_id::text, deleted, updated_at, is_business
                     from public.transactions where recurring_id = '{r1}';""").rows
    row = row[0] if row else [""] * 14
    expect_id = db.run(f"select extensions.uuid_generate_v5('{r1}'::uuid, '{today}');").cell()
    check("its id is uuid_generate_v5(rule id, 'YYYY-MM-DD')", row[0] == expect_id, (row[0], expect_id))
    check("it belongs to the rule's owner and wallet", row[1] == a_id and row[3] == wa, row[1:4])
    check("it copies type, amount, category, merchant, note",
          row[2] == "expense" and row[4] == "123456" and row[6] == "bills" and row[7] == "Due Today Landlord"
          and row[8] == "check note", row)
    check("its date is the due date, source is 'recurring', recurring_id is the rule",
          row[5] == today and row[9] == "recurring" and row[10] == r1, row)
    check("it is not deleted and not marked as a business expense", row[11] == "f" and row[13] == "f", row)
    check("updated_at is epoch milliseconds of now", row[12].isdigit() and t0 - 2000 <= int(row[12]) <= t1 + 2000, row[12])
    check("the rule moved on one month", next_of(r1) == py_next("monthly", today_d, None).isoformat(), next_of(r1))
    r = db.run(f"select count(*) from public.transactions where recurring_id = '{r1}' and rev > {cursor_before};")
    check("rev moved past the pull cursor, so phones will pull the new row", r.cell() == "1", r.rows)
    r = db.run(f"select rev > {rule_cursor_before}, updated_at from public.recurring where id = '{r1}';")
    check("the rule's rev moved too, so phones pull the new next_date", r.rows and r.rows[0][0] == "t", r.rows)
    check("the rule's updated_at was NOT touched (the system's advance is not an edit)",
          r.rows and r.rows[0][1] == "1000", r.rows)

    res = generate()
    check("running it again returns 0", res.cell() == "0" and res.ok, (res.rows, res.messages))
    check("... and still exactly one transaction", count_of(r1) == 1, count_of(r1))
    # A phone that had not pulled the advance pushes the old next_date back, with a newer updated_at.
    db.run(f"update public.recurring set next_date = '{today}', updated_at = 2000 where id = '{r1}';")
    res = generate()
    check("a phone pushing the old next_date back creates no duplicate", count_of(r1) == 1 and res.cell() == "0",
          (count_of(r1), res.rows))
    check("... and the rule is moved on again", next_of(r1) == py_next("monthly", today_d, None).isoformat(), next_of(r1))
    db.run("set datestyle = 'SQL, DMY';")
    db.run(f"update public.recurring set next_date = '{today}' where id = '{r1}';")
    generate()
    check("a different DateStyle does not change ids: still one transaction", count_of(r1) == 1, count_of(r1))
    db.run("set datestyle = 'ISO, YMD';")

    # -----------------------------------------------------------------------------------------------
    print("month-end clamping and the anchor day")
    m31 = add_rule(a_id, wa, "monthly", "2026-01-31", merchant="Month end")
    generate("2026-05-01", only=[m31])
    check("Jan 31 monthly -> Jan 31, Feb 28, Mar 31, Apr 30",
          dates_of(m31) == "2026-01-31,2026-02-28,2026-03-31,2026-04-30", dates_of(m31))
    check("... and the rule waits for May 31, not May 28", next_of(m31) == "2026-05-31", next_of(m31))
    leap = add_rule(a_id, wa, "monthly", "2028-01-31")
    generate("2028-03-01", only=[leap])
    check("in a leap year Jan 31 -> Feb 29 -> Mar 31", dates_of(leap) == "2028-01-31,2028-02-29", dates_of(leap))
    clamped = add_rule(a_id, wa, "monthly", "2026-02-28", anchor=31)
    generate("2026-04-30", only=[clamped])
    check("a rule already on Feb 28 with anchor_day 31 goes Mar 31, Apr 30",
          dates_of(clamped) == "2026-02-28,2026-03-31,2026-04-30", dates_of(clamped))
    legacy = add_rule(a_id, wa, "monthly", "2026-02-28")
    generate("2026-04-30", only=[legacy])
    check("with no anchor_day it follows the day of next_date (28th)",
          dates_of(legacy) == "2026-02-28,2026-03-28,2026-04-28", dates_of(legacy))
    pin = add_rule(a_id, wa, "monthly", "2026-01-31")
    generate("2026-01-31", only=[pin])
    check("a run that stops on a clamped date (next_date Feb 28) has pinned anchor_day 31 on the rule",
          next_of(pin) == "2026-02-28" and db.run(f"select anchor_day from public.recurring where id = '{pin}';").cell() == "31",
          (next_of(pin), db.run(f"select anchor_day from public.recurring where id = '{pin}';").rows))
    generate("2026-03-31", only=[pin])
    check("... so the next run goes Feb 28, Mar 31, not Mar 28",
          dates_of(pin) == "2026-01-31,2026-02-28,2026-03-31", dates_of(pin))
    leapday = add_rule(a_id, wa, "yearly", "2024-02-29")
    generate("2028-03-01", only=[leapday])
    check("Feb 29 yearly -> Feb 28 in common years, Feb 29 again in 2028",
          dates_of(leapday) == "2024-02-29,2025-02-28,2026-02-28,2027-02-28,2028-02-29", dates_of(leapday))
    weekly = add_rule(a_id, wa, "weekly", "2026-11-02")
    generate("2026-11-23", only=[weekly])
    check("weekly from Mon Nov 2 -> 2, 9, 16, 23, next Nov 30",
          dates_of(weekly) == "2026-11-02,2026-11-09,2026-11-16,2026-11-23" and next_of(weekly) == "2026-11-30",
          (dates_of(weekly), next_of(weekly)))
    yearly = add_rule(a_id, wa, "yearly", "2025-06-15")
    generate("2027-06-14", only=[yearly])
    check("yearly June 15 2025 -> only 2025 and 2026 by Jun 14 2027",
          dates_of(yearly) == "2025-06-15,2026-06-15" and next_of(yearly) == "2027-06-15", (dates_of(yearly), next_of(yearly)))

    # -----------------------------------------------------------------------------------------------
    print("catch-up after three missed months")
    cu = add_rule(a_id, wa, "monthly", "2026-08-15", merchant="Catch up")
    generate("2026-08-15", only=[cu])
    check("the Aug 15 run creates August", dates_of(cu) == "2026-08-15", dates_of(cu))
    # The job does not run in September, October or November, then runs on Nov 20.
    res = generate("2026-11-20", only=[cu])
    check("the next run, on Nov 20, creates September, October and November",
          dates_of(cu) == "2026-08-15,2026-09-15,2026-10-15,2026-11-15", dates_of(cu))
    check("... next_date is Dec 15", next_of(cu) == "2026-12-15", next_of(cu))
    check("... each period exactly once", count_of(cu) == 4, count_of(cu))
    generate("2026-11-20", only=[cu])
    check("a repeat of that run changes nothing", count_of(cu) == 4 and next_of(cu) == "2026-12-15", count_of(cu))

    # -----------------------------------------------------------------------------------------------
    print("paused, deleted and deleted-transaction cases")
    paused = add_rule(a_id, wa, "monthly", "2026-01-10", active=False)
    gone = add_rule(a_id, wa, "monthly", "2026-01-10", deleted=True)
    generate("2026-06-30", only=[paused, gone])
    check("a paused rule creates nothing and is not advanced", count_of(paused) == 0 and next_of(paused) == "2026-01-10",
          (count_of(paused), next_of(paused)))
    check("a deleted rule creates nothing and is not advanced", count_of(gone) == 0 and next_of(gone) == "2026-01-10",
          (count_of(gone), next_of(gone)))
    db.run(f"update public.recurring set active = true where id = '{paused}';")
    generate("2026-06-30", only=[paused, gone])
    check("resumed with its old date, it catches up from January", count_of(paused) == 6, count_of(paused))
    # a generated transaction the user deleted on the phone
    db.run(f"update public.transactions set deleted = true, "
           f"updated_at = (extract(epoch from clock_timestamp()) * 1000)::bigint + 1000 "
           f"where recurring_id = '{m31}' and date = '2026-02-28';")  # newer than the stored row, or lww_guard keeps the old one
    db.run(f"update public.recurring set next_date = '2026-01-31', updated_at = 6000 where id = '{m31}';")
    generate("2026-05-01", only=[m31])
    r = db.run(f"select count(*), count(*) filter (where deleted) from public.transactions where recurring_id = '{m31}';")
    check("a deleted generated transaction is not brought back and not duplicated", r.rows == [["4", "1"]], r.rows)
    # an archived wallet
    db.run(f"update public.wallets set deleted = true where id = '{wb}';")
    arch = add_rule(b_id, wb, "monthly", "2026-07-01")
    generate("2026-07-01", only=[arch])
    check("a rule on an archived wallet still generates (the wallet shows as archived on the phone)",
          count_of(arch) == 1, count_of(arch))

    # -----------------------------------------------------------------------------------------------
    print("synced rows: who sees them")
    ctx = lambda uid: ("savepoint s; set local role authenticated; "
                       f"select set_config('request.jwt.claim.sub', '{uid}', true), "
                       f"set_config('request.jwt.claims', '{{\"sub\":\"{uid}\",\"role\":\"authenticated\"}}', true);")
    r = db.run(ctx(a_id) + f"select count(*) from public.transactions where recurring_id = '{r1}'; rollback to savepoint s;")
    check("the owner sees the generated row through RLS", r.rows and r.rows[-1] == ["1"], r.messages or r.rows)
    r = db.run(ctx(b_id) + f"select count(*) from public.transactions where recurring_id = '{r1}'; rollback to savepoint s;")
    check("another user does not", r.rows and r.rows[-1] == ["0"], r.messages or r.rows)
    r = db.run(f"select count(*) from public.transactions where source = 'recurring' and recurring_id = '{cu}' "
               "and rev > 0 and updated_at > 1000000000000;")
    check("every generated row has a rev and an epoch-ms updated_at", r.cell() == "4", r.rows)

    # -----------------------------------------------------------------------------------------------
    print("one bad rule does not stop the others")
    db.run("""create function public.chimu_check_boom() returns trigger language plpgsql as
              $$ begin if new.merchant = 'BOOM' then raise exception 'boom'; end if; return new; end $$;
              create trigger chimu_check_boom before insert on public.transactions
                for each row execute function public.chimu_check_boom();""")
    boom = add_rule(a_id, wa, "monthly", "2026-09-01", merchant="BOOM")
    fine = add_rule(b_id, wb, "monthly", "2026-09-01", merchant="Fine")
    res = generate("2026-09-01", only=[boom, fine])
    check("the run finishes, with a warning that names the bad rule",
          res.ok and any(boom in w for w in res.warnings), res.messages)
    check("the good rule was generated", count_of(fine) == 1, count_of(fine))
    check("the bad rule created nothing and was not advanced", count_of(boom) == 0 and next_of(boom) == "2026-09-01",
          (count_of(boom), next_of(boom)))
    db.run("drop trigger chimu_check_boom on public.transactions; drop function public.chimu_check_boom();")
    generate("2026-09-01", only=[boom, fine])
    check("once the problem is gone, the next run generates it", count_of(boom) == 1, count_of(boom))

    # -----------------------------------------------------------------------------------------------
    print("the per-run cap")
    cap = add_rule(a_id, wa, "weekly", "2000-01-03", amount=100, merchant="Old weekly")
    generate("2026-11-23", only=[cap])
    check("a rule hundreds of periods behind makes 366 on the first run", count_of(cap) == 366 and
          datetime.date.fromisoformat(next_of(cap)) <= datetime.date(2026, 11, 23), (count_of(cap), next_of(cap)))
    for _ in range(6):
        generate("2026-11-23", only=[cap])
    expected = (datetime.date(2026, 11, 23) - datetime.date(2000, 1, 3)).days // 7 + 1
    check("a few more runs finish the catch-up exactly, with no duplicates",
          count_of(cap) == expected and next_of(cap) == "2026-11-30", (count_of(cap), expected, next_of(cap)))

    # -----------------------------------------------------------------------------------------------
    print("income rules")
    inc = add_rule(a_id, wa, "monthly", "2026-10-01", rtype="income", category="salary", merchant="Employer")
    generate("2026-10-01", only=[inc])
    r = db.run(f"select type, category, to_wallet_id is null from public.transactions where recurring_id = '{inc}';")
    check("an income rule makes an income transaction with no second wallet", r.rows == [["income", "salary", "t"]], r.rows)


def main():
    dsn = os.environ.get("SUPABASE_DB_URL", "").strip()
    db = Psql(dsn)
    try:
        run(db)
    finally:
        db.close()
    failed = [name for ok, name in results if not ok]
    print(f"\n{len(results) - len(failed)}/{len(results)} checks passed")
    for name in failed:
        print(f"  FAILED: {name}")
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()
