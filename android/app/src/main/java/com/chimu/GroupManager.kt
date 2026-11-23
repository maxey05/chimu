package com.chimu

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * The four group RPCs from RFC-001 (DESIGN.md -> Visibility). Membership changes go through these
 * only (RULES 16). [SupaGroupApi] is the real one. Network and server trouble arrive as
 * [SyncUnavailable] (nothing changed, try again) and [SyncRejected] (the server said no; its
 * message is already in words for the user).
 */
interface GroupApi {
    suspend fun createGroup(name: String): Group

    suspend fun joinGroup(code: String): Group

    suspend fun leaveGroup(groupId: String)

    suspend fun removeMember(groupId: String, userId: String)
}

sealed interface GroupResult {
    /**
     * It worked on the server. [group] is the group created or joined. [synced] says whether the sync
     * that follows a membership change (the full re-pull) also finished; if not, the lists catch up at the next sync.
     */
    data class Done(val group: Group? = null, val synced: Boolean = true) : GroupResult

    /** Nothing changed. [words] says why, for the user. */
    data class Failed(val words: String) : GroupResult
}

/**
 * Create, join, leave and remove (RFC-006). It checks the input first (RULES 18), calls the server,
 * and after a success runs a sync, which sees the new membership and starts the full re-pull of
 * RFC-003. It never touches the phone's own data: the lists change only when that sync brings the
 * server's answer.
 *
 * @param afterChange runs a sync and says whether it finished. Called only after the server accepted
 *   the change, and it is allowed to finish even if the screen that asked has gone away.
 */
class GroupManager(
    private val api: GroupApi,
    private val afterChange: suspend () -> Boolean,
) {
    private val _selected = MutableStateFlow<String?>(null)

    /** The group the Group tab shows, or null for "the first one". Kept for as long as the app runs. */
    val selected: StateFlow<String?> = _selected.asStateFlow()

    fun select(groupId: String?) {
        _selected.value = groupId
    }

    suspend fun create(rawName: String): GroupResult {
        val name = GroupRules.cleanName(rawName) ?: return GroupResult.Failed(GroupText.NAME_HELP)
        return attempt { api.createGroup(name).also { select(it.id) } }
    }

    suspend fun join(rawCode: String): GroupResult {
        val code = GroupRules.cleanCode(rawCode) ?: return GroupResult.Failed(GroupText.CODE_HELP)
        return attempt { api.joinGroup(code).also { select(it.id) } }
    }

    suspend fun leave(groupId: String): GroupResult = attempt {
        api.leaveGroup(groupId)
        if (_selected.value == groupId) select(null)
        null
    }

    suspend fun remove(groupId: String, userId: String): GroupResult = attempt {
        api.removeMember(groupId, userId)
        null
    }

    private suspend fun attempt(call: suspend () -> Group?): GroupResult {
        val group = try {
            call()
        } catch (e: CancellationException) {
            throw e
        } catch (e: SyncUnavailable) {
            return GroupResult.Failed(GroupText.UNREACHABLE)
        } catch (e: SyncRejected) {
            return GroupResult.Failed(e.message?.takeIf { it.isNotBlank() } ?: GroupText.SERVER_NO)
        } catch (e: Exception) {
            // Unknown: the server may or may not have acted (say, an answer we couldn't read). Sync anyway,
            // so the lists show what is true, and tell the user honestly that this didn't go as planned.
            withContext(NonCancellable) { runCatching { afterChange() } }
            return GroupResult.Failed("Something went wrong (${e.message ?: e::class.simpleName}). Pull down to refresh and check the group list.")
        }
        // The server accepted it. Finish the sync even if the user has already left the screen.
        val synced = withContext(NonCancellable) { runCatching { afterChange() }.getOrDefault(false) }
        return GroupResult.Done(group, synced)
    }
}
