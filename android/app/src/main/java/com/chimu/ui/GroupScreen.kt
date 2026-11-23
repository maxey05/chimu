package com.chimu.ui

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.chimu.Categories
import com.chimu.ChimuApp
import com.chimu.Group
import com.chimu.GroupFilter
import com.chimu.GroupLedger
import com.chimu.GroupMember
import com.chimu.GroupResult
import com.chimu.GroupRules
import com.chimu.GroupSnapshot
import com.chimu.GroupText
import com.chimu.Ledger
import com.chimu.Money
import com.chimu.ReceiptText
import com.chimu.SyncText
import com.chimu.Txn
import com.chimu.TxnType
import java.time.YearMonth
import kotlinx.coroutines.launch

/**
 * The Group tab (RFC-006): switch between groups, the invite code, members with this month's totals,
 * and the combined feed of everyone's entries. Everything is read from what this phone has saved, so
 * it works offline; the heading says when that was last brought up to date. Other people's entries are
 * view-only and open a detail screen. Create, join, leave and remove need the internet and change nothing
 * if they can't reach the server (RULES 16, 20).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupScreen(
    app: ChimuApp,
    onCreate: () -> Unit,
    onJoin: () -> Unit,
    onOpenOwn: (String) -> Unit,
    onOpenShared: (String) -> Unit,
) {
    val snap by app.repo.groups.collectAsState()
    val selectedId by app.groups.selected.collectAsState()
    val sync by app.sync.status.collectAsState()
    val group = GroupRules.pick(selectedId, snap.groups)
    val freshness = GroupText.freshness(
        if (sync.running) "Syncing now" else SyncText.lastSynced(sync.lastSyncedAt, System.currentTimeMillis()),
    )

    PullToRefreshBox(
        isRefreshing = sync.running,
        onRefresh = { app.sync.requestNow() },
        modifier = Modifier.fillMaxSize(),
    ) {
        if (group == null) {
            NoGroup(freshness, onCreate, onJoin)
        } else {
            GroupContent(app, snap, group, freshness, onCreate, onJoin, onOpenOwn, onOpenShared)
        }
    }
}

@Composable
private fun Heading(freshness: String) {
    Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 8.dp)) {
        Text("Group", style = MaterialTheme.typography.headlineMedium)
        Text(freshness, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun NoGroup(freshness: String, onCreate: () -> Unit, onJoin: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Heading(freshness)
        Column(Modifier.padding(horizontal = 20.dp)) {
            Text(
                "A group lets the people in it see each other's spending and totals, for example your family.",
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                GroupText.SHARING_NOTICE,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 12.dp),
            )
            Spacer(Modifier.height(24.dp))
            Button(onClick = onCreate, modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchHeight)) {
                Text("Create a group")
            }
            Spacer(Modifier.height(12.dp))
            OutlinedButton(onClick = onJoin, modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchHeight)) {
                Text("Join with a code")
            }
        }
    }
}

@Composable
private fun GroupContent(
    app: ChimuApp,
    snap: GroupSnapshot,
    group: Group,
    freshness: String,
    onCreate: () -> Unit,
    onJoin: () -> Unit,
    onOpenOwn: (String) -> Unit,
    onOpenShared: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val month = remember { YearMonth.now().toString() }
    val members = remember(snap, group.id) { snap.membersOf(group.id) }
    val totals = remember(snap, group.id, month) { GroupLedger.memberTotals(snap.txns, members, month).associateBy { it.member.userId } }

    // Filters belong to one group and survive rotation.
    var memberFilter by rememberSaveable(group.id) { mutableStateOf<String?>(null) }
    var categoryFilter by rememberSaveable(group.id) { mutableStateOf<String?>(null) }
    val activeMember = memberFilter?.takeIf { id -> members.any { it.userId == id } }
    val feed = remember(snap, group.id, activeMember, categoryFilter) {
        GroupLedger.feed(snap.txns, members.map { it.userId }.toSet(), GroupFilter(activeMember, categoryFilter))
    }

    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmLeave by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf<GroupMember?>(null) }

    fun perform(call: suspend () -> GroupResult, done: String) {
        busy = true
        message = null
        error = null
        scope.launch {
            when (val result = call()) {
                is GroupResult.Done -> message = if (result.synced) done else "$done The lists will update at the next sync."
                is GroupResult.Failed -> error = result.words
            }
            busy = false
        }
    }

    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item { Heading(freshness) }

        if (snap.groups.size > 1) {
            item {
                DropdownField(
                    label = "Group",
                    valueText = group.name,
                    choices = snap.groups.map { Choice(it.id, it.name) },
                    onSelect = { id -> app.groups.select(id) },
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                )
            }
        } else {
            item { Text(group.name, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) }
        }

        item {
            Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
                Column(Modifier.padding(20.dp)) {
                    Text("Invite code", style = MaterialTheme.typography.labelLarge)
                    Text(
                        GroupText.codeForDisplay(group.inviteCode),
                        style = MaterialTheme.typography.headlineMedium,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    Text(
                        "Anyone with this code can join, and then sees everything members log, including income.",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = {
                                copyInviteCode(context, group)
                                message = "Invite code copied."
                                error = null
                            },
                            modifier = Modifier.heightIn(min = MinTouchHeight),
                        ) { Text("Copy code") }
                        OutlinedButton(
                            onClick = {
                                if (!shareInvite(context, group)) error = "This phone has no app to share with. Copy the code instead."
                            },
                            modifier = Modifier.heightIn(min = MinTouchHeight),
                        ) { Text("Share") }
                    }
                }
            }
        }

        item {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                message?.let { Text(it, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold) }
                ErrorText(error)
            }
        }

        item {
            Text(
                "Members, ${Ledger.monthLabel(month)}",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 4.dp),
            )
            HorizontalDivider()
        }
        items(members, key = { it.userId }) { member ->
            val t = totals[member.userId]?.totals
            Row(
                modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp).padding(horizontal = 20.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(member.label, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                    Text(GroupText.roleLabel(member.role), style = MaterialTheme.typography.bodySmall)
                    Text(
                        "Spent ${Money.format(t?.spent ?: 0)} · Income ${Money.format(t?.income ?: 0)}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                if (GroupRules.canRemove(members, member)) {
                    TextButton(onClick = { removing = member }, enabled = !busy, modifier = Modifier.heightIn(min = MinTouchHeight)) {
                        Text("Remove")
                    }
                }
            }
            HorizontalDivider()
        }

        item {
            Text(
                "Everyone's entries",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 8.dp),
            )
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilterMenu(
                    title = "Member",
                    valueText = members.firstOrNull { it.userId == activeMember }?.label ?: "Everyone",
                    choices = listOf(Choice(null, "Everyone")) + members.map { Choice(it.userId, it.label) },
                    onSelect = { memberFilter = it },
                )
                Spacer(Modifier.width(8.dp))
                FilterMenu(
                    title = "Category",
                    valueText = categoryFilter?.let { Categories.label(it) } ?: "All",
                    choices = listOf(Choice(null, "All")) + Categories.all.map { Choice(it.slug, it.label) },
                    onSelect = { categoryFilter = it },
                )
            }
            Spacer(Modifier.height(8.dp))
            HorizontalDivider()
        }

        if (feed.isEmpty()) {
            item {
                Text(
                    GroupText.feedEmpty(activeMember != null || categoryFilter != null),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(20.dp),
                )
            }
        } else {
            items(feed, key = { it.id }) { txn ->
                val mine = txn.ownerId == snap.me
                FeedRow(snap, txn, mine, onClick = { if (mine) onOpenOwn(txn.id) else onOpenShared(txn.id) })
                HorizontalDivider()
            }
        }

        item {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = onCreate, modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchHeight)) {
                    Text("Create another group")
                }
                OutlinedButton(onClick = onJoin, modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchHeight)) {
                    Text("Join another group")
                }
                if (GroupRules.canLeave(members)) {
                    OutlinedButton(
                        onClick = { confirmLeave = true },
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchHeight),
                    ) { Text("Leave group") }
                } else {
                    Text(
                        "You started this group, so you can leave once everyone else has left or been removed.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }

    if (confirmLeave) {
        val alone = members.size == 1
        AlertDialog(
            onDismissRequest = { confirmLeave = false },
            title = { Text("Leave ${group.name}?") },
            text = {
                Text(
                    if (alone) {
                        "You are the only member, so leaving deletes the group and its invite code."
                    } else {
                        "You will stop seeing the other members' entries, and they will stop seeing yours. What you logged stays yours."
                    },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmLeave = false
                        perform({ app.groups.leave(group.id) }, "You left ${group.name}.")
                    },
                    modifier = Modifier.heightIn(min = MinTouchHeight),
                ) { Text("Leave group") }
            },
            dismissButton = { TextButton(onClick = { confirmLeave = false }, modifier = Modifier.heightIn(min = MinTouchHeight)) { Text("Stay") } },
        )
    }

    removing?.let { target ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text("Remove ${target.label}?") },
            text = { Text("They will stop seeing your entries, and you will stop seeing theirs, unless you share another group.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        removing = null
                        perform({ app.groups.remove(group.id, target.userId) }, "${target.label} was removed.")
                    },
                    modifier = Modifier.heightIn(min = MinTouchHeight),
                ) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { removing = null }, modifier = Modifier.heightIn(min = MinTouchHeight)) { Text("Keep") } },
        )
    }
}

/** One entry in the combined feed. Someone else's reads "View only" in words, so it never relies on colour (RULES 21). */
@Composable
private fun FeedRow(snap: GroupSnapshot, txn: Txn, mine: Boolean, onClick: () -> Unit) {
    val who = GroupText.personLabel(snap.names[txn.ownerId], mine)
    val title = when (txn.type) {
        TxnType.TRANSFER -> "Transfer"
        else -> txn.merchant?.takeIf { it.isNotBlank() } ?: Categories.label(txn.category).ifBlank { txn.type.label }
    }
    val detail = when (txn.type) {
        TxnType.TRANSFER -> "$who · ${wallet(snap, txn.walletId)} to ${wallet(snap, txn.toWalletId)}"
        else -> "$who · ${txn.type.label} · ${Categories.label(txn.category).ifBlank { "No category" }}"
    }
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).heightIn(min = 72.dp).padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
            Text(detail, style = MaterialTheme.typography.bodyMedium)
            Text(Ledger.dateLabel(txn.date), style = MaterialTheme.typography.bodySmall)
            // Only the user's own scans carry a reading state; what a member's phone is doing is not synced.
            if (mine) ReceiptText.listNote(txn)?.let { Text(it, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold) }
            else Text("View only", style = MaterialTheme.typography.bodySmall)
        }
        Text(
            if (mine) ReceiptText.listAmount(txn) else Money.formatSigned(txn.type, txn.amount),
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.End,
            modifier = Modifier.padding(start = 12.dp),
        )
    }
}

private fun wallet(snap: GroupSnapshot, id: String?): String = snap.walletNames[id] ?: "a wallet"

/** Puts the invite code on the clipboard. */
internal fun copyInviteCode(context: Context, group: Group) {
    context.getSystemService(ClipboardManager::class.java)
        ?.setPrimaryClip(ClipData.newPlainText("Chimu invite code", group.inviteCode))
}

/** Opens Android's share sheet with the invite message. False if the phone has nothing to share with. */
internal fun shareInvite(context: Context, group: Group): Boolean {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, GroupText.shareMessage(group))
    }
    return try {
        context.startActivity(Intent.createChooser(send, "Share the invite code"))
        true
    } catch (e: ActivityNotFoundException) {
        false
    }
}
