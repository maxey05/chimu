package com.chimu

/**
 * Tracking groups on the phone (RFC-006). Everything here is pure: the rules for what a group
 * screen shows, so they can be tested without a phone or a network.
 *
 * What the phone holds: the groups the user is in, who is in them, display names, and a read-only
 * cache of every member's transactions (pulled by sync, DESIGN.md -> Visibility). The cache can hold
 * more than one group's worth of people, or (after someone else signed in on the same phone) people
 * the current user no longer shares anything with, so every view starts from [GroupRules.snapshot],
 * which keeps only the groups the user is in and the people in them.
 */

/** A tracking group as cached from the server. */
data class Group(val id: String, val name: String, val inviteCode: String, val ownerId: String)

/** One `group_members` row. [role] is `owner` or `member`. */
data class Membership(val groupId: String, val userId: String, val role: String)

/** One person in a group, as the Group screen lists them. */
data class GroupMember(val userId: String, val role: String, val isMe: Boolean, val label: String) {
    val isOwner: Boolean get() = role == "owner"
}

/** Spent and income for one member in one month. */
data class MemberTotals(val member: GroupMember, val totals: MonthTotals)

/** The Group screen's feed filter. Null = no filter. */
data class GroupFilter(val memberId: String? = null, val category: String? = null)

/**
 * What the Group tab reads. Built by [GroupRules.snapshot], so it holds only the signed-in user's
 * own groups, the people in those groups, and those people's live, confirmed transactions.
 */
data class GroupSnapshot(
    val me: String = "",
    val groups: List<Group> = emptyList(),
    val memberships: List<Membership> = emptyList(),
    /** User id to display name, for the people in [groups]. A blank name is left out. */
    val names: Map<String, String> = emptyMap(),
    /** Own and other members' transactions, newest first is not promised: use [GroupLedger.feed]. */
    val txns: List<Txn> = emptyList(),
    /** Wallet id to wallet name, for the wallets of the people in [groups] (archived ones included). */
    val walletNames: Map<String, String> = emptyMap(),
) {
    fun membersOf(groupId: String): List<GroupMember> = GroupRules.members(this, groupId)
}

/** The `WHERE` clause for the cached transactions the Group tab reads, as plain text so it can be run against a real SQLite. */
object GroupSql {
    /** Every cached transaction that is live and not an unconfirmed Inbox item (RULES 17). Others' rows never carry `inbox`; the phone's own can. */
    const val CACHED_TXNS_WHERE = "deleted = 0 AND status <> 'inbox'"
}

object GroupRules {
    const val NAME_MAX = 60
    const val CODE_LENGTH = 6

    /** The characters `new_invite_code()` draws from (supabase/migrations/0001_init.sql): A-Z and 2-9 without I and O. */
    const val CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"

    /** A group name the server will accept (1 to 60 characters once trimmed), or null. Counted in characters, as Postgres does, not UTF-16 units. */
    fun cleanName(raw: String): String? {
        val name = raw.trim()
        return name.takeIf { it.codePointCount(0, it.length) in 1..NAME_MAX }
    }

    /** An invite code as the server stores it (upper case, no spaces or dashes), or null if it can't be one. */
    fun cleanCode(raw: String): String? {
        val code = raw.filterNot { it.isWhitespace() || it == '-' }.uppercase()
        return code.takeIf { it.length == CODE_LENGTH && it.all { c -> c in CODE_ALPHABET } }
    }

    /**
     * Narrows what the phone has cached to what [me] may see in a group view: the groups [me] belongs
     * to, the memberships and names of the people in them, and those people's transactions and wallet
     * names. Everything else in the cache (another account's leftovers, people from a group [me] has left)
     * is dropped here, whatever sync has or hasn't cleaned up yet.
     */
    fun snapshot(
        me: String,
        groups: List<Group>,
        memberships: List<Membership>,
        names: Map<String, String>,
        txns: List<Txn>,
        wallets: List<Wallet>,
    ): GroupSnapshot {
        val mine = memberships.filter { it.userId == me }.map { it.groupId }.toSet()
        val myGroups = groups.filter { it.id in mine }.sortedWith(compareBy({ it.name.lowercase() }, { it.id }))
        val myMemberships = memberships.filter { it.groupId in myGroups.map { g -> g.id }.toSet() }
        val people = myMemberships.map { it.userId }.toSet()
        return GroupSnapshot(
            me = me,
            groups = myGroups,
            memberships = myMemberships,
            names = names.filter { (id, name) -> id in people && name.isNotBlank() },
            txns = txns.filter { it.ownerId in people && !it.deleted && it.status != "inbox" },
            walletNames = wallets.filter { it.ownerId in people }.associate { it.id to it.name },
        )
    }

    /** The group to show: the one the user picked if it is still there, else the first. Null with no groups. */
    fun pick(selectedId: String?, groups: List<Group>): Group? =
        groups.firstOrNull { it.id == selectedId } ?: groups.firstOrNull()

    /** The people in [groupId]: the person who started it first, then the others by name. */
    fun members(snapshot: GroupSnapshot, groupId: String): List<GroupMember> =
        snapshot.memberships
            .filter { it.groupId == groupId }
            .map { m ->
                GroupMember(
                    userId = m.userId,
                    role = m.role,
                    isMe = m.userId == snapshot.me,
                    label = GroupText.personLabel(snapshot.names[m.userId], m.userId == snapshot.me),
                )
            }
            .sortedWith(compareBy<GroupMember>({ !it.isOwner }, { it.label.lowercase() }, { it.userId }))

    /** The owner can leave only when nobody else is in the group (the server's rule; this saves a pointless call). Anyone else can always leave. */
    fun canLeave(members: List<GroupMember>): Boolean {
        val me = members.firstOrNull { it.isMe } ?: return false
        return !me.isOwner || members.size == 1
    }

    /** Only the person who started the group can remove people, and never themself. */
    fun canRemove(members: List<GroupMember>, target: GroupMember): Boolean =
        members.firstOrNull { it.isMe }?.isOwner == true && !target.isMe
}

object GroupLedger {
    /**
     * The group's combined feed: live transactions of [memberIds], newest first. An unconfirmed Inbox
     * item and a deleted row never show. [filter] narrows to one member and/or one category.
     */
    fun feed(txns: List<Txn>, memberIds: Set<String>, filter: GroupFilter = GroupFilter()): List<Txn> {
        val visible = txns.filter {
            it.ownerId in memberIds && !it.deleted && it.status != "inbox" &&
                (filter.memberId == null || it.ownerId == filter.memberId)
        }
        return Ledger.filter(visible, TxnFilter(category = filter.category))
    }

    /** Each member's spent and income for [month] (`YYYY-MM`), in [members]' order. Transfers count as neither; a member with nothing logged gets zeros. */
    fun memberTotals(txns: List<Txn>, members: List<GroupMember>, month: String): List<MemberTotals> {
        val visible = txns.filter { !it.deleted && it.status != "inbox" }
        return members.map { member ->
            MemberTotals(member, Ledger.monthTotals(visible.filter { it.ownerId == member.userId }, month))
        }
    }
}

/** The words the Group screens use. */
object GroupText {
    /** RULES 23 and RFC-006: the join screen must say exactly this. */
    const val SHARING_NOTICE = "Everyone in this group will see everything you log, including income."

    const val NAME_HELP = "Give the group a name of 1 to 60 characters."
    const val CODE_HELP = "An invite code has 6 letters and numbers, like K7M4QX. It never has 0, 1, I or O."

    /** Offline, a timeout, an expired sign-in or a server fault: nothing changed on the server. */
    const val UNREACHABLE =
        "Couldn't reach the server, or your sign-in has expired. Check your internet connection and try again. Nothing was changed."

    const val SERVER_NO = "The server couldn't do that. Try again in a moment."

    /** A person's name as listed: their display name, `(you)` after your own, and a plain word when no name is known. */
    fun personLabel(name: String?, isMe: Boolean): String {
        val clean = name?.trim().orEmpty()
        return when {
            isMe && clean.isEmpty() -> "You"
            isMe -> "$clean (you)"
            clean.isEmpty() -> "A group member"
            else -> clean
        }
    }

    fun roleLabel(role: String): String = if (role == "owner") "Started this group" else "Member"

    /** `K7M4QX` -> `K7M 4QX`, easier to read out loud. */
    fun codeForDisplay(code: String): String = code.chunked(3).joinToString(" ")

    /** The text the share sheet sends. It repeats the sharing notice so nobody joins unaware. */
    fun shareMessage(group: Group): String =
        "Join \"${group.name}\" on Chimu with the invite code ${group.inviteCode}. " +
            "Everyone in the group sees everything each member logs, including income."

    /** The line under the Group heading: the lists come from what this phone saved, and when that was. [lastSynced] is [SyncText.lastSynced]'s answer. */
    fun freshness(lastSynced: String): String = "Showing what this phone has saved. $lastSynced."

    fun feedEmpty(filtering: Boolean): String =
        if (filtering) "Nothing matches these filters." else "No entries yet. What members log will show up here."

    /**
     * Turns what the server said about a refused group request into words for the user. [raw] is the
     * server's message, or the JSON error body around it; it is matched by phrase so either works.
     */
    fun serverWords(raw: String?): String {
        val text = raw?.lowercase().orEmpty()
        return when {
            "invalid invite code" in text -> "That invite code isn't right. Check it with the person who gave it to you."
            "group name must be" in text -> NAME_HELP
            "owner cannot leave" in text ->
                "You started this group, so you can leave only after everyone else has left or been removed."
            "only the group owner" in text -> "Only the person who started the group can remove members."
            "cannot remove themself" in text -> "You can't remove yourself. Use Leave group instead."
            "not in this group" in text -> "That person isn't in this group. Pull down to refresh the list."
            "not signed in" in text -> "You're not signed in. Sign in again and retry."
            "invite code" in text && "try again" in text -> "Couldn't make an invite code just now. Try again."
            else -> SERVER_NO
        }
    }
}
