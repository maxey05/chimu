package com.chimu.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.chimu.ChimuApp
import com.chimu.Group
import com.chimu.GroupResult
import com.chimu.GroupText
import kotlinx.coroutines.launch

/**
 * "Create a group" (RFC-006): name it, then see its invite code with Copy and Share. The sharing
 * notice is here too, because the person who starts a group shares everything they log with whoever joins.
 */
@Composable
fun CreateGroupScreen(app: ChimuApp, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var name by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var created by remember { mutableStateOf<Group?>(null) }
    var notSynced by remember { mutableStateOf(false) }
    var copied by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState())) {
        FormHeader("Create a group", onBack = onClose)
        Column(Modifier.padding(20.dp)) {
            val group = created
            if (group == null) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Group name") },
                    singleLine = true,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                )
                NoticeCard(Modifier.padding(top = 16.dp))
                ErrorText(error, Modifier.padding(top = 12.dp))
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = {
                        busy = true
                        error = null
                        scope.launch {
                            when (val result = app.groups.create(name)) {
                                is GroupResult.Done -> {
                                    created = result.group
                                    notSynced = !result.synced
                                }
                                is GroupResult.Failed -> error = result.words
                            }
                            busy = false
                        }
                    },
                    enabled = !busy && name.isNotBlank(),
                    modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchHeight),
                ) { Text(if (busy) "Creating..." else "Create group") }
            } else {
                Text("${group.name} is ready.", style = MaterialTheme.typography.titleLarge)
                Text(
                    "Give people this code. They enter it under Group, then Join with a code.",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(top = 8.dp),
                )
                Card(Modifier.fillMaxWidth().padding(top = 16.dp)) {
                    Column(Modifier.padding(20.dp)) {
                        Text("Invite code", style = MaterialTheme.typography.labelLarge)
                        Text(
                            GroupText.codeForDisplay(group.inviteCode),
                            style = MaterialTheme.typography.headlineLarge,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                OutlinedButton(
                    onClick = {
                        copyInviteCode(context, group)
                        copied = true
                    },
                    modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchHeight),
                ) { Text(if (copied) "Copied" else "Copy code") }
                Spacer(Modifier.height(12.dp))
                OutlinedButton(
                    onClick = { if (!shareInvite(context, group)) error = "This phone has no app to share with. Copy the code instead." },
                    modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchHeight),
                ) { Text("Share") }
                ErrorText(error, Modifier.padding(top = 12.dp))
                if (notSynced) {
                    Text(
                        "The group will show on the Group tab after the next sync.",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
                Spacer(Modifier.height(16.dp))
                Button(onClick = onClose, modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchHeight)) { Text("Done") }
            }
        }
    }
}

/**
 * "Join a group" (RFC-006, RULES 23). The sentence that everyone in the group sees everything you log,
 * income included, is on this screen above the button, always visible, in words.
 */
@Composable
fun JoinGroupScreen(app: ChimuApp, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    var code by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var joined by remember { mutableStateOf<Group?>(null) }
    var notSynced by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState())) {
        FormHeader("Join a group", onBack = onClose)
        Column(Modifier.padding(20.dp)) {
            val group = joined
            if (group == null) {
                NoticeCard()
                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it },
                    label = { Text("Invite code") },
                    supportingText = { Text("6 letters and numbers, from the person who started the group.") },
                    singleLine = true,
                    enabled = !busy,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                    modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                )
                ErrorText(error, Modifier.padding(top = 12.dp))
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = {
                        busy = true
                        error = null
                        scope.launch {
                            when (val result = app.groups.join(code)) {
                                is GroupResult.Done -> {
                                    joined = result.group
                                    notSynced = !result.synced
                                }
                                is GroupResult.Failed -> error = result.words
                            }
                            busy = false
                        }
                    },
                    enabled = !busy && code.isNotBlank(),
                    modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchHeight),
                ) { Text(if (busy) "Joining..." else "Join group") }
            } else {
                Text("You joined ${group.name}.", style = MaterialTheme.typography.titleLarge)
                Text(
                    if (notSynced) {
                        "Its entries will show on the Group tab after the next sync."
                    } else {
                        "You can see its members and entries on the Group tab."
                    },
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(top = 8.dp),
                )
                Spacer(Modifier.height(16.dp))
                Button(onClick = onClose, modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchHeight)) { Text("Done") }
            }
        }
    }
}

/** The sharing notice in a card, so it stands out from the form. */
@Composable
private fun NoticeCard(modifier: Modifier = Modifier) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("Before you continue", style = MaterialTheme.typography.labelLarge)
            Text(
                GroupText.SHARING_NOTICE,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}
