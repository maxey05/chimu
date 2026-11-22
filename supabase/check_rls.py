#!/usr/bin/env python3
"""Check Chimu's row level security, last-writer-wins and group rules.

Runs against a real Supabase project, using only the anon key (never the
service-role key) and the Python standard library.

    SUPABASE_URL=https://<ref>.supabase.co SUPABASE_ANON_KEY=... python supabase/check_rls.py

It signs up three throwaway users (A, B, C), asserts the rules from DESIGN.md
-> Visibility and Sync protocol, then cleans up what it can. Needs email
confirmation OFF (Auth -> Providers -> Email) so sign-up returns a session.

Leftovers: signed-up users cannot delete themselves, so three auth users named
chimu-check-*@example.com remain per run, and the wallet/transaction rows stay
as tombstones (deleted = true, the app never hard-deletes). Remove the users
from the dashboard (Authentication -> Users) or run in the SQL editor:
    delete from auth.users where email like 'chimu-check-%@example.com';
That cascades to their profiles, rows and memberships.

Exit code 0 = every assertion passed, 1 = at least one failed.
"""
import base64
import json
import os
import sys
import time
import urllib.error
import urllib.request
import uuid

EMAIL_DOMAIN = "example.com"
# A real 2x2 JPEG, so the bucket's image/jpeg restriction is satisfied.
JPEG = base64.b64decode(
    "/9j/4AAQSkZJRgABAQAAAQABAAD/2wBDABALDA4MChAODQ4SERATGCgaGBYWGDEjJR0oOjM9PDkzODdASFxOQERXRTc4UG1RV19iZ2hn"
    "Pk1xeXBkeFxlZ2P/2wBDARESEhgVGC8aGi9jQjhCY2NjY2NjY2NjY2NjY2NjY2NjY2NjY2NjY2NjY2NjY2NjY2NjY2NjY2NjY2NjY2Nj"
    "Y2P/wAARCAACAAIDASIAAhEBAxEB/8QAHwAAAQUBAQEBAQEAAAAAAAAAAAECAwQFBgcICQoL/8QAtRAAAgEDAwIEAwUFBAQAAAF9AQID"
    "AAQRBRIhMUEGE1FhByJxFDKBkaEII0KxwRVS0fAkM2JyggkKFhcYGRolJicoKSo0NTY3ODk6Q0RFRkdISUpTVFVWV1hZWmNkZWZnaGlq"
    "c3R1dnd4eXqDhIWGh4iJipKTlJWWl5iZmqKjpKWmp6ipqrKztLW2t7i5usLDxMXGx8jJytLT1NXW19jZ2uHi4+Tl5ufo6erx8vP09fb3"
    "+Pn6/8QAHwEAAwEBAQEBAQEBAQAAAAAAAAECAwQFBgcICQoL/8QAtREAAgECBAQDBAcFBAQAAQJ3AAECAxEEBSExBhJBUQdhcRMiMoEI"
    "FEKRobHBCSMzUvAVYnLRChYkNOEl8RcYGRomJygpKjU2Nzg5OkNERUZHSElKU1RVVldYWVpjZGVmZ2hpanN0dXZ3eHl6goOEhYaHiImK"
    "kpOUlZaXmJmaoqOkpaanqKmqsrO0tba3uLm6wsPExcbHyMnK0tPU1dbX2Nna4uPk5ebn6Onq8vP09fb3+Pn6/9oADAMBAAIRAxEAPwDH"
    "ooooThPqD/9k="
)

results = []  # (ok, name)


def check(name, ok, detail=""):
    results.append((bool(ok), name))
    print(f"  {'PASS' if ok else 'FAIL'}  {name}" + (f"  [{detail}]" if detail and not ok else ""))
    return bool(ok)


def http(method, url, key, token=None, body=None, headers=None, raw=None):
    """Return (status, parsed_json_or_bytes). Never raises on HTTP errors."""
    h = {"apikey": key, "Authorization": f"Bearer {token or key}"}
    data = raw
    if body is not None:
        data = json.dumps(body).encode()
        h["Content-Type"] = "application/json"
    h.update(headers or {})
    req = urllib.request.Request(url, data=data, method=method, headers=h)
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            status, payload = r.status, r.read()
    except urllib.error.HTTPError as e:
        status, payload = e.code, e.read()
    if payload and h.get("Accept") != "raw":
        try:
            return status, json.loads(payload)
        except ValueError:
            pass
    return status, payload


class Api:
    def __init__(self, base, key):
        self.base, self.key = base.rstrip("/"), key

    def signup(self, label):
        email = f"chimu-check-{uuid.uuid4().hex[:12]}-{label}@{EMAIL_DOMAIN}"
        status, body = http("POST", f"{self.base}/auth/v1/signup", self.key,
                            body={"email": email, "password": uuid.uuid4().hex + "Aa1!"})
        if status != 200 or not isinstance(body, dict) or "access_token" not in body:
            sys.exit(f"sign-up for {email} failed ({status}): {body}\n"
                     "Is email confirmation turned off, and is the email domain accepted?")
        return {"token": body["access_token"], "id": body["user"]["id"], "email": email}

    def rest(self, method, path, who, body=None, prefer="return=representation"):
        headers = {"Prefer": prefer} if prefer else {}
        return http(method, f"{self.base}/rest/v1/{path}", self.key,
                    token=who["token"] if who else None, body=body, headers=headers)

    def rpc(self, fn, who, args):
        return self.rest("POST", f"rpc/{fn}", who, body=args, prefer=None)

    def put_photo(self, who, path):
        return http("POST", f"{self.base}/storage/v1/object/receipts/{path}", self.key,
                    token=who["token"], raw=JPEG, headers={"Content-Type": "image/jpeg"})

    def get_photo(self, who, path):
        return http("GET", f"{self.base}/storage/v1/object/authenticated/receipts/{path}", self.key,
                    token=who["token"], headers={"Accept": "raw"})

    def delete_photo(self, who, path):
        return http("DELETE", f"{self.base}/storage/v1/object/receipts/{path}", self.key,
                    token=who["token"])


def now_ms():
    return int(time.time() * 1000)


def tx_row(owner, wallet_id, tx_id, updated_at, note="first", **extra):
    row = {"id": tx_id, "type": "expense", "wallet_id": wallet_id, "amount": 12345,
           "date": "2026-11-22", "category": "food", "merchant": "Check Cafe",
           "note": note, "updated_at": updated_at}
    if owner:
        row["owner_id"] = owner["id"]
    row.update(extra)
    return row


def is_error(status):
    return status >= 400


def ids(body):
    return [r["id"] for r in body] if isinstance(body, list) else None


def hidden(body, row_id):
    """True only for a real list response that does not contain row_id."""
    return isinstance(body, list) and row_id not in ids(body)


def run(api):
    anon = None  # no user token: requests go out with the anon key only

    print("sign-up and profiles")
    a, b, c = api.signup("a"), api.signup("b"), api.signup("c")
    s, body = api.rest("GET", f"profiles?id=eq.{a['id']}", a)
    check("sign-up created a profile row for A", s == 200 and len(body) == 1 and body[0]["display_name"], (s, body))
    s, body = api.rest("GET", f"profiles?id=eq.{a['id']}", b)
    check("B cannot see A's profile before sharing a group", s == 200 and body == [], (s, body))

    print("anon has no access")
    s, body = api.rest("GET", "transactions?select=id", anon)
    check("anon cannot read transactions", s in (401, 403), (s, body))
    s, body = api.rpc("join_group", anon, {"code": "ABCDEF"})
    check("anon cannot call join_group", s in (401, 403), (s, body))

    print("A's rows are private before any group")
    wallet_a, tx_a = str(uuid.uuid4()), str(uuid.uuid4())
    t0 = now_ms()
    s, body = api.rest("POST", "wallets", a, {"id": wallet_a, "name": "A cash", "type": "cash",
                                              "opening_balance": 0, "updated_at": t0})
    check("A can insert a wallet", s == 201 and len(body) == 1 and body[0]["owner_id"] == a["id"], (s, body))
    s, body = api.rest("POST", "transactions", a, tx_row(None, wallet_a, tx_a, t0))
    ok = s == 201 and len(body) == 1
    check("A can insert a transaction (owner_id defaults to A)", ok and body[0]["owner_id"] == a["id"], (s, body))
    rev1 = body[0]["rev"] if ok else 0
    check("rev is assigned on insert", rev1 > 0, rev1)
    s, body = api.rest("GET", f"transactions?id=eq.{tx_a}", a)
    check("A can read their own transaction", s == 200 and ids(body) == [tx_a], (s, body))
    for who, label in ((b, "B"), (c, "C")):
        s, body = api.rest("GET", "transactions?select=id", who)
        check(f"{label} sees none of A's transactions", s == 200 and hidden(body, tx_a), (s, body))
        s, body = api.rest("GET", "wallets?select=id", who)
        check(f"{label} sees none of A's wallets", s == 200 and hidden(body, wallet_a), (s, body))

    print("groups")
    s, group = api.rpc("create_group", a, {"name": "  Check Family  "})
    code = group.get("invite_code") if isinstance(group, dict) else None
    check("A creates a group; the name is trimmed", s == 200 and group.get("name") == "Check Family", (s, group))
    check("invite code is 6 characters of A-Z / 0-9", isinstance(code, str) and len(code) == 6 and code.isalnum()
          and code == code.upper(), code)
    s, body = api.rest("GET", "group_members", a)
    check("A is the only member, as owner", s == 200 and len(body) == 1 and body[0]["role"] == "owner", (s, body))
    s, body = api.rpc("create_group", a, {"name": "   "})
    check("a blank group name is rejected", is_error(s), (s, body))
    s, body = api.rpc("join_group", c, {"code": "ZZZZZZ"})
    check("joining with a wrong code fails", is_error(s), (s, body))
    s, body = api.rest("POST", "group_members", b, {"group_id": group["id"], "user_id": b["id"], "role": "member"})
    check("B cannot add themself to a group by direct insert", is_error(s), (s, body))
    s, body = api.rest("POST", "groups", b, {"name": "sneaky", "invite_code": "AAAAAA", "owner_id": b["id"]})
    check("B cannot create a group by direct insert", is_error(s), (s, body))

    s, body = api.rpc("join_group", b, {"code": code.lower()})
    check("B joins A's group (code is case-insensitive)", s == 200 and body.get("id") == group["id"], (s, body))
    s, body = api.rpc("join_group", b, {"code": code})
    check("joining twice is harmless", s == 200, (s, body))
    s, body = api.rest("GET", "group_members", b)
    check("B sees both members", s == 200 and len(body) == 2, (s, body))
    s, body = api.rest("GET", "groups", b)
    check("B can read the group", s == 200 and ids(body) == [group["id"]], (s, body))
    s, body = api.rest("GET", "groups", c)
    check("C (no group) cannot read the group", s == 200 and body == [], (s, body))
    s, body = api.rest("GET", "group_members", c)
    check("C (no group) sees no memberships", s == 200 and body == [], (s, body))

    print("after B joins: read-only access to A's rows")
    s, body = api.rest("GET", f"transactions?id=eq.{tx_a}", b)
    check("B can now read A's transaction", s == 200 and ids(body) == [tx_a], (s, body))
    s, body = api.rest("GET", f"wallets?id=eq.{wallet_a}", b)
    check("B can now read A's wallet", s == 200 and ids(body) == [wallet_a], (s, body))
    s, body = api.rest("GET", f"profiles?id=eq.{a['id']}", b)
    check("B can now see A's profile", s == 200 and len(body) == 1, (s, body))
    s, body = api.rest("GET", "transactions?select=id", c)
    check("C still sees none of A's transactions", s == 200 and hidden(body, tx_a), (s, body))

    s, body = api.rest("PATCH", f"transactions?id=eq.{tx_a}", b, {"note": "hijacked", "updated_at": now_ms() + 5000})
    check("B's update of A's transaction changes nothing", not is_error(s) and body == [], (s, body))
    s, body = api.rest("POST", "transactions", b, tx_row(a, wallet_a, str(uuid.uuid4()), now_ms()))
    check("B cannot insert a row owned by A", is_error(s), (s, body))
    s, body = api.rest("POST", "transactions", b, tx_row(None, wallet_a, str(uuid.uuid4()), now_ms()))
    check("B cannot insert a row that points at A's wallet", is_error(s), (s, body))
    s, body = api.rest("POST", "transactions?on_conflict=id", b,
                       tx_row(b, wallet_a, tx_a, now_ms() + 5000, note="takeover"),
                       prefer="resolution=merge-duplicates,return=representation")
    check("B cannot take over A's row id with an upsert", is_error(s), (s, body))
    s, body = api.rest("POST", "wallets", b, {"id": wallet_a, "name": "mine now", "type": "cash",
                                               "updated_at": now_ms() + 5000},
                       prefer="resolution=merge-duplicates,return=representation")
    check("B cannot take over A's wallet id with an upsert", is_error(s), (s, body))
    s, body = api.rest("GET", f"transactions?id=eq.{tx_a}", a)
    check("A's transaction is untouched", s == 200 and body[0]["note"] == "first", (s, body))
    s, body = api.rest("DELETE", f"transactions?id=eq.{tx_a}", b)
    check("B cannot delete A's transaction", is_error(s) or body == [], (s, body))
    s, body = api.rest("DELETE", f"transactions?id=eq.{tx_a}", a)
    check("nobody can hard-delete a transaction (not even A)", is_error(s), (s, body))

    # B's own rows are fine, and A can read them: sharing is symmetric.
    wallet_b, tx_b = str(uuid.uuid4()), str(uuid.uuid4())
    api.rest("POST", "wallets", b, {"id": wallet_b, "name": "B gcash", "type": "gcash", "updated_at": now_ms()})
    s, body = api.rest("POST", "transactions", b, tx_row(None, wallet_b, tx_b, now_ms(), note="from B"))
    check("B can insert their own transaction", s == 201, (s, body))
    s, body = api.rest("GET", f"transactions?id=eq.{tx_b}", a)
    check("A can read B's transaction", s == 200 and ids(body) == [tx_b], (s, body))

    print("budgets, recurring and goals follow the same rules")
    budget_a, rec_a, goal_a = str(uuid.uuid4()), str(uuid.uuid4()), str(uuid.uuid4())
    s, body = api.rest("POST", "budgets", a, {"id": budget_a, "group_id": group["id"], "category": "food",
                                              "monthly_limit": 500000, "updated_at": now_ms()})
    check("A can create a group budget for their own group", s == 201, (s, body))
    s, body = api.rest("POST", "budgets", c, {"id": str(uuid.uuid4()), "group_id": group["id"],
                                              "monthly_limit": 1, "updated_at": now_ms()})
    check("C cannot create a budget pointing at a group C is not in", is_error(s), (s, body))
    s, body = api.rest("POST", "recurring", a, {"id": rec_a, "type": "expense", "wallet_id": wallet_a, "amount": 99900,
                                                "frequency": "monthly", "next_date": "2026-12-01",
                                                "updated_at": now_ms()})
    check("A can create a recurring rule", s == 201, (s, body))
    s, body = api.rest("POST", "recurring", a, {"id": str(uuid.uuid4()), "type": "transfer", "wallet_id": wallet_a,
                                                "amount": 1, "frequency": "monthly", "next_date": "2026-12-01",
                                                "updated_at": now_ms()})
    check("a recurring transfer is rejected", is_error(s), (s, body))
    s, body = api.rest("POST", "goals", a, {"id": goal_a, "name": "Trip", "target": 5000000, "wallet_id": wallet_a,
                                            "updated_at": now_ms()})
    check("A can create a goal", s == 201, (s, body))
    s, body = api.rest("POST", "goals", c, {"id": str(uuid.uuid4()), "name": "Steal", "target": 1,
                                            "wallet_id": wallet_a, "updated_at": now_ms()})
    check("C cannot attach a goal to A's wallet", is_error(s), (s, body))
    for table, key in (("budgets", budget_a), ("recurring", rec_a), ("goals", goal_a)):
        s, body = api.rest("GET", f"{table}?id=eq.{key}", b)
        check(f"B can read A's {table} row", s == 200 and ids(body) == [key], (s, body))
        s, body = api.rest("PATCH", f"{table}?id=eq.{key}", b, {"updated_at": now_ms() + 9000})
        check(f"B cannot edit A's {table} row", not is_error(s) and body == [], (s, body))
        s, body = api.rest("GET", f"{table}?id=eq.{key}", c)
        check(f"C cannot see A's {table} row", s == 200 and body == [], (s, body))

    print("membership RPC rules")
    s, body = api.rpc("remove_member", b, {"group_id": group["id"], "user_id": a["id"]})
    check("a plain member cannot remove anyone", is_error(s), (s, body))
    s, body = api.rpc("remove_member", a, {"group_id": group["id"], "user_id": a["id"]})
    check("the owner cannot remove themself", is_error(s), (s, body))
    s, body = api.rpc("leave_group", a, {"group_id": group["id"]})
    check("the owner cannot leave while others remain", is_error(s), (s, body))
    s, body = api.rpc("leave_group", c, {"group_id": group["id"]})
    check("a non-member cannot leave someone else's group", is_error(s), (s, body))

    print("last-writer-wins and rev")
    s, body = api.rest("GET", f"transactions?id=eq.{tx_a}", a)
    rev_before = body[0]["rev"]
    stale = tx_row(a, wallet_a, tx_a, t0 - 60_000, note="stale write")
    s, body = api.rest("POST", "transactions?on_conflict=id", a, stale,
                       prefer="resolution=merge-duplicates,return=representation")
    check("an older updated_at upsert is accepted without error", not is_error(s) and len(body) == 1, (s, body))
    s, body = api.rest("GET", f"transactions?id=eq.{tx_a}", a)
    check("...but does not overwrite the newer row", body[0]["note"] == "first" and body[0]["updated_at"] == t0, body)
    check("...and does not bump rev", body[0]["rev"] == rev_before, (body[0]["rev"], rev_before))

    fresh = tx_row(a, wallet_a, tx_a, t0 + 60_000, note="newer write")
    s, body = api.rest("POST", "transactions?on_conflict=id", a, fresh,
                       prefer="resolution=merge-duplicates,return=representation")
    rev_after_1 = body[0]["rev"] if not is_error(s) and body else 0
    check("a newer updated_at upsert overwrites", not is_error(s) and body[0]["note"] == "newer write", (s, body))
    check("rev increased on the accepted write", rev_after_1 > rev_before, (rev_before, rev_after_1))

    s, body = api.rest("PATCH", f"transactions?id=eq.{tx_a}", a, {"note": "patched", "updated_at": t0 + 120_000})
    rev_after_2 = body[0]["rev"] if not is_error(s) and body else 0
    check("a PATCH with a newer updated_at is accepted", not is_error(s) and body[0]["note"] == "patched", (s, body))
    check("rev increased again", rev_after_2 > rev_after_1, (rev_after_1, rev_after_2))

    s, body = api.rest("PATCH", f"transactions?id=eq.{tx_a}", a, {"note": "stale patch", "updated_at": t0 - 1})
    check("a PATCH with an older updated_at keeps the stored row",
          not is_error(s) and body[0]["note"] == "patched" and body[0]["rev"] == rev_after_2, (s, body))

    s, body = api.rest("GET", f"transactions?rev=gt.{rev_before}&order=rev&select=id,rev", a)
    check("pulling with rev > cursor returns the changed row", s == 200 and tx_a in (ids(body) or []), (s, body))
    api.rest("PATCH", f"transactions?id=eq.{tx_a}", a, {"owner_id": b["id"], "updated_at": t0 + 180_000})
    s, body = api.rest("GET", f"transactions?id=eq.{tx_a}", a)
    check("an update cannot change owner_id (the row stays A's)", s == 200 and ids(body) == [tx_a]
          and body[0]["owner_id"] == a["id"], (s, body))
    s, body = api.rest("PATCH", f"transactions?id=eq.{tx_a}", a, {"rev": 1, "updated_at": t0 + 190_000})
    check("a client cannot set rev", not is_error(s) and body[0]["rev"] > rev_after_2, (s, body))

    print("data constraints")
    s, body = api.rest("POST", "transactions", a, tx_row(None, wallet_a, str(uuid.uuid4()), now_ms(), amount=-1))
    check("a negative amount is rejected", is_error(s), (s, body))
    s, body = api.rest("POST", "transactions", a, tx_row(None, wallet_a, str(uuid.uuid4()), now_ms(), type="barter"))
    check("an unknown transaction type is rejected", is_error(s), (s, body))
    s, body = api.rest("POST", "transactions", a, tx_row(None, wallet_a, str(uuid.uuid4()), now_ms(), type="transfer"))
    check("a transfer without a destination wallet is rejected", is_error(s), (s, body))
    s, body = api.rest("POST", "wallets", a, {"id": str(uuid.uuid4()), "name": "x", "type": "crypto", "updated_at": now_ms()})
    check("an unknown wallet type is rejected", is_error(s), (s, body))

    print("receipt photos (storage)")
    photo = f"{a['id']}/{tx_a}.jpg"
    s, body = api.put_photo(a, photo)
    check("A can upload to their own folder", s == 200, (s, body))
    s, body = api.get_photo(a, photo)
    check("A can read their own photo", s == 200 and body == JPEG, (s,))
    s, body = api.get_photo(b, photo)
    check("B can read A's photo while sharing a group", s == 200 and body == JPEG, (s,))
    s, body = api.get_photo(c, photo)
    check("C cannot read A's photo", s != 200, (s,))
    s, body = api.put_photo(b, f"{a['id']}/{uuid.uuid4()}.jpg")
    check("B cannot upload into A's folder", s != 200, (s, body))
    s, body = api.delete_photo(b, photo)
    s2, body2 = api.get_photo(a, photo)
    check("B cannot delete A's photo", s2 == 200, (s, s2))

    print("after B leaves")
    s, body = api.rpc("leave_group", b, {"group_id": group["id"]})
    check("B leaves the group", not is_error(s), (s, body))
    s, body = api.rest("GET", f"transactions?id=eq.{tx_a}", b)
    check("A's transaction is invisible to B again", s == 200 and body == [], (s, body))
    s, body = api.rest("GET", f"wallets?id=eq.{wallet_a}", b)
    check("A's wallet is invisible to B again", s == 200 and body == [], (s, body))
    for table, key in (("budgets", budget_a), ("recurring", rec_a), ("goals", goal_a)):
        s, body = api.rest("GET", f"{table}?id=eq.{key}", b)
        check(f"A's {table} row is invisible to B again", s == 200 and body == [], (s, body))
    s, body = api.rest("GET", f"profiles?id=eq.{a['id']}", b)
    check("A's profile is invisible to B again", s == 200 and body == [], (s, body))
    s, body = api.get_photo(b, photo)
    check("A's photo is unreadable to B again", s != 200, (s,))
    s, body = api.rest("GET", f"transactions?id=eq.{tx_b}", a)
    check("B's rows are invisible to A too", s == 200 and body == [], (s, body))
    s, body = api.rest("GET", f"transactions?id=eq.{tx_b}", b)
    check("B still sees their own rows", s == 200 and ids(body) == [tx_b], (s, body))
    s, body = api.rest("GET", "groups", b)
    check("B can no longer read the group", s == 200 and body == [], (s, body))

    print("C never saw anything")
    for table in ("wallets", "transactions", "budgets", "recurring", "goals", "groups", "group_members"):
        s, body = api.rest("GET", table, c)
        check(f"C sees no rows in {table}", s == 200 and body == [], (s, body))

    return dict(a=a, b=b, c=c, group=group, tx_a=tx_a, wallet_a=wallet_a, tx_b=tx_b, wallet_b=wallet_b, photo=photo,
                budget_a=budget_a, rec_a=rec_a, goal_a=goal_a)


def cleanup(api, ctx):
    """Best effort. Rows become tombstones; groups and photos are removed."""
    print("cleanup")
    a, b = ctx["a"], ctx["b"]
    stamp = now_ms() + 600_000
    api.delete_photo(a, ctx["photo"])
    for who, table, key in ((a, "transactions", ctx["tx_a"]), (b, "transactions", ctx["tx_b"]),
                            (a, "budgets", ctx["budget_a"]), (a, "recurring", ctx["rec_a"]),
                            (a, "goals", ctx["goal_a"]),
                            (a, "wallets", ctx["wallet_a"]), (b, "wallets", ctx["wallet_b"])):
        api.rest("PATCH", f"{table}?id=eq.{key}", who, {"deleted": True, "updated_at": stamp})
    s, _ = api.rpc("leave_group", a, {"group_id": ctx["group"]["id"]})  # last member: removes the group
    print(f"  tombstoned test rows, removed photo and group (leave_group -> {s})")
    print("  left behind: 3 auth users chimu-check-*@example.com (see the top of this file)")


def main():
    url, key = os.environ.get("SUPABASE_URL"), os.environ.get("SUPABASE_ANON_KEY")
    if not url or not key:
        sys.exit("set SUPABASE_URL and SUPABASE_ANON_KEY")
    try:
        role = json.loads(base64.urlsafe_b64decode(key.split(".")[1] + "=="))["role"]
    except Exception:
        role = None
    if role == "service_role":
        sys.exit("refusing to run with a service-role key: use the anon key (RULES 13)")

    api = Api(url, key)
    ctx = None
    try:
        ctx = run(api)
    finally:
        if ctx:
            cleanup(api, ctx)

    failed = [name for ok, name in results if not ok]
    print(f"\n{len(results) - len(failed)}/{len(results)} assertions passed")
    for name in failed:
        print(f"  FAILED: {name}")
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()
