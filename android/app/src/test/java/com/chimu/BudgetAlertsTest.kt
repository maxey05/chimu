package com.chimu

import java.time.LocalDate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When a budget alert is posted, and when it is not (RFC-009 acceptance: "crossing 80% then 100% gives exactly one
 * notification each per month", "another member's expense pushes a group budget over: you get alerted after sync").
 * The notification itself ([BudgetNotifier]) is Android; here it is a function that says yes or no.
 */
class BudgetAlertsTest {
    private val me = "me"
    private val month = "2026-11"

    private class MapStore : AlertStore {
        val map = HashMap<String, String>()
        override fun get(key: String): String? = map[key]
        override fun put(key: String, value: String) {
            map[key] = value
        }
    }

    private val store = MapStore()
    private val sent = mutableListOf<BudgetAlert>()
    private var accept = true
    private val alerts = BudgetAlerts(store) { alert ->
        if (accept) sent += alert
        accept
    }

    private fun food(id: String = "b1", limit: Long = 10_000, group: String? = null, owner: String = me) =
        Budget(id, owner, group, "food", limit)

    private fun spend(id: String, amount: Long, owner: String = me, date: String = "2026-11-10", category: String? = "food") = Txn(
        id = id, ownerId = owner, type = TxnType.EXPENSE, walletId = "w", toWalletId = null, amount = amount, date = date,
        category = category, merchant = null, note = null, isBusiness = false,
    )

    private val family = Group("g1", "Family", "CODE12", me)
    private val familyMembers = listOf(Membership("g1", me, "owner"), Membership("g1", "kim", "member"))

    private fun snap(txns: List<Txn> = emptyList()) =
        GroupSnapshot(me = me, groups = listOf(family), memberships = familyMembers, txns = txns)

    /** What Repo hands over after a reload, for [me], and the alerts that went out. */
    private fun reload(
        budgets: List<Budget>, txns: List<Txn>, who: String = me, snapshot: GroupSnapshot = snap(txns), alerts: BudgetAlerts = this.alerts,
        today: LocalDate = LocalDate.of(2026, 11, 24),
    ) = alerts.checkAll(who, budgets, txns.filter { it.ownerId == who }, snapshot, today)

    private fun levels() = sent.map { it.level }

    @Test
    fun crossing80ThenFull_postsExactlyOneEach_andRepeatsPostNothing() {
        val b = listOf(food())
        assertTrue(reload(b, listOf(spend("a", 5_000))).isEmpty())
        assertEquals(listOf(BudgetLevel.WARN), reload(b, listOf(spend("a", 5_000), spend("b", 3_000))).map { it.level })
        repeat(3) { assertTrue(reload(b, listOf(spend("a", 5_000), spend("b", 3_000))).isEmpty()) }
        val over = listOf(spend("a", 5_000), spend("b", 3_000), spend("c", 2_000))
        assertEquals(listOf(BudgetLevel.OVER), reload(b, over).map { it.level })
        repeat(3) { assertTrue(reload(b, over).isEmpty()) }
        assertEquals(listOf(BudgetLevel.WARN, BudgetLevel.OVER), levels())
        assertNotEquals(sent[0].notificationId, sent[1].notificationId)
        assertEquals("Food & Dining budget: 80% used", sent[0].title)
        assertEquals("₱80.00 of ₱100.00 spent in November 2026. Close to the limit.", sent[0].text)
        assertEquals("Food & Dining budget: 100% used", sent[1].title)
        assertEquals("₱100.00 of ₱100.00 spent in November 2026. At the limit.", sent[1].text)
    }

    @Test
    fun aDirectJumpToOver_postsOnlyTheOverAlert() {
        reload(listOf(food()), listOf(spend("a", 4_000)))
        reload(listOf(food()), listOf(spend("a", 4_000), spend("b", 9_000)))
        assertEquals(listOf(BudgetLevel.OVER), levels())
        assertEquals("Food & Dining budget: 130% used", sent.single().title)
    }

    @Test
    fun justBelow80_doesNotAlert_andWarnLevelIsNotRepeatedByMoreSpendingBelowOver() {
        val b = listOf(food(limit = 100_000))
        assertTrue(reload(b, listOf(spend("a", 79_900))).isEmpty())
        assertEquals(1, reload(b, listOf(spend("a", 80_000))).size)
        assertTrue(reload(b, listOf(spend("a", 99_999))).isEmpty())
    }

    @Test
    fun theRecordIsWrittenInTheAgreedFormat() {
        reload(listOf(food()), listOf(spend("a", 8_000)))
        assertEquals("2026-11:80:10000", store.map["budget_alert_me_b1"])
        reload(listOf(food()), listOf(spend("a", 10_000)))
        assertEquals("2026-11:100:10000", store.map["budget_alert_me_b1"])
        assertEquals(1, store.map.size)
    }

    @Test
    fun aGroupBudget_anotherMembersExpensePushesItOver_andYouAreAlerted() {
        val kimsBudget = food(id = "gb", limit = 10_000, group = "g1", owner = "kim") // kim owns it; I am just a member
        val mine = spend("m1", 3_000)
        assertTrue("nothing yet", reload(listOf(kimsBudget), listOf(mine)).isEmpty())
        // After the next sync kim's rows are in the cache and in the snapshot.
        val kims = spend("k1", 8_000, owner = "kim")
        val posted = reload(listOf(kimsBudget), listOf(mine, kims))
        assertEquals(listOf(BudgetLevel.OVER), posted.map { it.level })
        assertEquals("Group Family · Food & Dining budget: 110% used", posted.single().title)
        assertEquals("₱110.00 of ₱100.00 spent in November 2026. Over the limit by ₱10.00.", posted.single().text)
        assertTrue(reload(listOf(kimsBudget), listOf(mine, kims)).isEmpty())
    }

    @Test
    fun aGroupBudgetOwnedByMe_alertsMeToo() {
        val b = food(id = "gb", limit = 10_000, group = "g1")
        reload(listOf(b), listOf(spend("k1", 8_500, owner = "kim")))
        assertEquals(listOf(BudgetLevel.WARN), levels())
    }

    @Test
    fun aPersonalBudgetOfSomeoneElse_neverAlertsMe() {
        val kimsPersonal = food(id = "kp", limit = 100, owner = "kim")
        reload(listOf(kimsPersonal), listOf(spend("k1", 8_500, owner = "kim")))
        assertTrue(sent.isEmpty())
    }

    @Test
    fun aNewMonth_alertsAgain() {
        val b = listOf(food())
        reload(b, listOf(spend("a", 9_000)))
        assertEquals(1, sent.size)
        val december = listOf(spend("a", 9_000), spend("d", 8_500, date = "2026-12-02"))
        assertEquals(listOf(BudgetLevel.WARN), reload(b, december, today = LocalDate.of(2026, 12, 3)).map { it.level })
        assertEquals("2026-12:80:10000", store.map["budget_alert_me_b1"])
        assertEquals(2, sent.size)
        assertTrue(reload(b, december, today = LocalDate.of(2026, 12, 4)).isEmpty())
    }

    @Test
    fun changingTheLimit_resetsTheRecord() {
        val spent = listOf(spend("a", 10_000))
        reload(listOf(food(limit = 10_000)), spent) // OVER
        assertEquals(1, sent.size)
        // Raised to 20,000: now 50%, nothing to say, and the old OVER record must not stand.
        assertTrue(reload(listOf(food(limit = 20_000)), spent).isEmpty())
        assertEquals("2026-11:0:20000", store.map["budget_alert_me_b1"])
        // More spending takes it to 85% of the new limit: that is a fresh crossing of 80%.
        val more = spent + spend("b", 7_000)
        reload(listOf(food(limit = 20_000)), more)
        assertEquals(listOf(BudgetLevel.OVER, BudgetLevel.WARN), levels())
    }

    @Test
    fun raisingTheLimit_toOneTheSpendingIsAlreadyNear_alertsAtOnce() {
        val spent = listOf(spend("a", 12_000))
        reload(listOf(food(limit = 10_000)), spent) // OVER, recorded
        val posted = reload(listOf(food(limit = 14_000)), spent) // 85% of the new limit, in the same check as the edit
        assertEquals(listOf(BudgetLevel.WARN), posted.map { it.level })
        assertEquals("2026-11:80:14000", store.map["budget_alert_me_b1"])
    }

    @Test
    fun loweringTheLimitBackToAnAlertedOne_alertsAgain_becauseTheEditResetIt() {
        val spent = listOf(spend("a", 12_000))
        reload(listOf(food(limit = 10_000)), spent) // OVER
        reload(listOf(food(limit = 30_000)), spent) // raised: 40%, reset
        val again = reload(listOf(food(limit = 10_000)), spent) // lowered again: over again
        assertEquals(listOf(BudgetLevel.OVER), again.map { it.level })
        assertEquals(2, sent.size)
    }

    @Test
    fun whenThePostFails_nothingIsRecorded_andALaterCheckPosts() {
        accept = false
        val b = listOf(food())
        val txns = listOf(spend("a", 8_500))
        assertTrue(reload(b, txns).isEmpty())
        assertTrue(sent.isEmpty())
        assertNull(store.get("budget_alert_me_b1"))
        assertTrue(reload(b, txns).isEmpty()) // still blocked, still owed
        accept = true // the user grants the permission
        assertEquals(listOf(BudgetLevel.WARN), reload(b, txns).map { it.level })
        assertTrue(reload(b, txns).isEmpty())
    }

    @Test
    fun aBlockedWarn_thenOver_postsOnlyOver() {
        accept = false
        reload(listOf(food()), listOf(spend("a", 8_500)))
        accept = true
        assertEquals(listOf(BudgetLevel.OVER), reload(listOf(food()), listOf(spend("a", 12_000))).map { it.level })
    }

    @Test
    fun aPostThatThrows_isTreatedAsNotSent_andDoesNotStopTheOtherBudgets() {
        val flaky = BudgetAlerts(store) { alert ->
            if (alert.budgetId == "bad") throw IllegalStateException("boom")
            sent += alert
            true
        }
        val budgets = listOf(food(id = "bad"), Budget("ok", me, null, null, 1_000))
        val txns = listOf(spend("a", 9_000))
        val posted = reload(budgets, txns, alerts = flaky)
        assertEquals(listOf("ok"), posted.map { it.budgetId }) // overall 900% over; the food one failed
        assertNull(store.get("budget_alert_me_bad"))
    }

    @Test
    fun deletingAnExpenseAndAddingItBack_doesNotAlertAgain() {
        val b = listOf(food())
        val full = listOf(spend("a", 6_000), spend("b", 4_000))
        reload(b, full) // OVER
        reload(b, listOf(spend("a", 6_000))) // one expense deleted: 60%
        assertTrue(reload(b, full).isEmpty()) // added back
        assertEquals(1, sent.size)
        // ...and a drop below 80 then back up to 80 does not repeat the 80% alert either.
        val c = listOf(food(id = "c", limit = 10_000))
        reload(c, listOf(spend("x", 8_000)))
        reload(c, listOf(spend("x", 1_000)))
        assertTrue(reload(c, listOf(spend("x", 8_000))).isEmpty())
    }

    @Test
    fun twoPeopleOnOnePhone_doNotShareRecords() {
        val b = listOf(food(owner = "kim", group = "g1")) // a group budget both see
        val txns = listOf(spend("k", 8_500, owner = "kim"))
        val kimSnapshot = GroupSnapshot(me = "kim", groups = listOf(family), memberships = familyMembers, txns = txns)
        assertEquals(1, reload(b, txns, who = "kim", snapshot = kimSnapshot).size)
        // The same phone, now signed in as me: kim's record does not hide my alert.
        assertEquals(1, reload(b, txns, who = me, snapshot = snap(txns)).size)
        assertEquals(setOf("budget_alert_kim_b1", "budget_alert_me_b1"), store.map.keys)
        assertTrue(reload(b, txns, who = "kim", snapshot = kimSnapshot).isEmpty())
        assertTrue(reload(b, txns, who = me, snapshot = snap(txns)).isEmpty())
    }

    @Test
    fun anUnsetLimit_neverAlerts() {
        for (limit in listOf(0L, -1L)) {
            reload(listOf(food(limit = limit)), listOf(spend("a", 999_999)))
        }
        assertTrue(sent.isEmpty())
        assertTrue(store.map.isEmpty())
    }

    @Test
    fun aDeletedBudget_neverAlerts_evenIfAHandedUsageStillHasIt() {
        val gone = food().copy(deleted = true)
        val usage = BudgetUsage(gone, 20_000, 200, BudgetLevel.OVER)
        assertTrue(alerts.check(me, listOf(usage), month).isEmpty())
        assertTrue(reload(listOf(gone), listOf(spend("a", 20_000))).isEmpty())
    }

    @Test
    fun aGarbledRecord_countsAsNoRecord() {
        for (junk in listOf("", "x", "2026-11:80", "2026-11:eighty:10000", "2026-11:80:lots")) {
            store.map["budget_alert_me_b1"] = junk
            sent.clear()
            assertEquals("record '$junk'", 1, reload(listOf(food()), listOf(spend("a", 8_500))).size)
        }
    }

    @Test
    fun notificationIds_areStablePerBudgetAndLevel() {
        assertEquals(BudgetAlerts.notificationId("b1", BudgetLevel.WARN), BudgetAlerts.notificationId("b1", BudgetLevel.WARN))
        assertNotEquals(BudgetAlerts.notificationId("b1", BudgetLevel.WARN), BudgetAlerts.notificationId("b1", BudgetLevel.OVER))
        assertNotEquals(BudgetAlerts.notificationId("b1", BudgetLevel.WARN), BudgetAlerts.notificationId("b2", BudgetLevel.WARN))
    }

    @Test
    fun manyThreadsChecking_atTheSameTime_postOnce() {
        val slow = BudgetAlerts(store) { alert ->
            Thread.sleep(20) // widen the window in which a second thread could also see "not sent yet"
            synchronized(sent) { sent += alert }
            true
        }
        val b = listOf(food())
        val txns = listOf(spend("a", 8_500))
        val pool = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)
        val done = CountDownLatch(8)
        repeat(8) {
            pool.execute {
                start.await()
                try {
                    reload(b, txns, alerts = slow)
                } finally {
                    done.countDown()
                }
            }
        }
        start.countDown()
        assertTrue(done.await(10, TimeUnit.SECONDS))
        pool.shutdown()
        assertEquals(1, sent.size)
    }
}
