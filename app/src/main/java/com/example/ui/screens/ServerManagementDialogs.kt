package com.example.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.data.model.ModerationLog
import com.example.data.model.Server
import com.example.data.model.ServerMember
import com.example.data.model.ServerPermission
import com.example.data.model.ServerRole
import com.example.data.model.UserProfile
import com.example.ui.components.UserAvatar
import com.example.ui.theme.LocalTalkbackColors

@Composable
fun ChannelEditorDialog(
    title: String,
    initialName: String,
    initialTopic: String,
    initialType: String,
    initialPosition: Long,
    allowTypeSelection: Boolean,
    onDismiss: () -> Unit,
    onSave: (name: String, topic: String, type: String, position: Long) -> Unit,
    onDelete: (() -> Unit)?
) {
    var name by remember { mutableStateOf(initialName) }
    var topic by remember { mutableStateOf(initialTopic) }
    var type by remember { mutableStateOf(initialType) }
    var positionText by remember { mutableStateOf(initialPosition.toString()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, modifier = Modifier.weight(1f))
                if (onDelete != null) {
                    IconButton(onClick = onDelete) {
                        Icon(Icons.Default.Delete, contentDescription = "Excluir canal", tint = MaterialTheme.colorScheme.error)
                    }
                }
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Nome do canal") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = topic,
                    onValueChange = { topic = it },
                    label = { Text("Tópico (opcional)") },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = positionText,
                    onValueChange = { positionText = it.filter { ch -> ch.isDigit() } },
                    label = { Text("Ordem (posição)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                if (allowTypeSelection) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = type == "text",
                            onClick = { type = "text" },
                            label = { Text("# Texto") }
                        )
                        FilterChip(
                            selected = type == "voice",
                            onClick = { type = "voice" },
                            label = { Text("🔊 Voz") }
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val pos = positionText.toLongOrNull() ?: initialPosition
                    onSave(name, topic, type, pos)
                },
                enabled = name.isNotBlank()
            ) { Text("Salvar") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } }
    )
}

@Composable
fun RolesManagerDialog(
    server: Server,
    roles: List<ServerRole>,
    onDismiss: () -> Unit,
    onSaveRole: (roleId: String?, name: String, colorHex: Long, perms: List<String>, pos: Long) -> Unit
) {
    var editingRoleId by remember { mutableStateOf<String?>(null) }
    var name by remember { mutableStateOf("") }
    val selectedPerms = remember { mutableStateListOf<String>().apply { addAll(ServerPermission.DEFAULT_MEMBER_WIRES) } }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Cargos e Permissões (${server.name})") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                LazyColumn(modifier = Modifier.height(140.dp)) {
                    items(roles, key = { it.id }) { role ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    editingRoleId = role.id
                                    name = role.name
                                    selectedPerms.clear()
                                    selectedPerms.addAll(role.permissions)
                                }
                                .padding(vertical = 6.dp)
                        ) {
                            Text(role.name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                            Text("${role.permissions.size} perm.", style = MaterialTheme.typography.bodySmall)
                        }
                        HorizontalDivider()
                    }
                }

                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(if (editingRoleId == null) "Novo cargo" else "Editar cargo") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                LazyColumn(modifier = Modifier.height(170.dp)) {
                    items(ServerPermission.entries, key = { it.wire }) { perm ->
                        val checked = selectedPerms.contains(perm.wire)
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    if (checked) selectedPerms.remove(perm.wire) else selectedPerms.add(perm.wire)
                                }
                        ) {
                            Checkbox(checked = checked, onCheckedChange = {
                                if (it) selectedPerms.add(perm.wire) else selectedPerms.remove(perm.wire)
                            })
                            Text(perm.labelPt, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSaveRole(editingRoleId, name, 0xFF5865F2L, selectedPerms.toList(), roles.size.toLong())
                    editingRoleId = null
                    name = ""
                },
                enabled = name.isNotBlank()
            ) { Text("Salvar cargo") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Fechar") } }
    )
}

@Composable
fun MembersManagerDialog(
    server: Server,
    currentUserId: String,
    myProfile: UserProfile?,
    members: List<ServerMember>,
    roles: List<ServerRole>,
    canManageMembers: Boolean,
    canManageRoles: Boolean,
    onDismiss: () -> Unit,
    onAssignRole: (targetUid: String, role: ServerRole) -> Unit,
    onKickOrBan: (targetUid: String, ban: Boolean, reason: String) -> Unit
) {
    val colors = LocalTalkbackColors.current
    var roleTargetMember by remember { mutableStateOf<ServerMember?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Membros (${members.size})") },
        text = {
            LazyColumn(modifier = Modifier.height(300.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(members, key = { it.userId }) { m ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        UserAvatar(displayName = m.displayName, avatarUrl = m.avatarUrl, size = 36.dp)
                        Column(modifier = Modifier.weight(1f).padding(horizontal = 8.dp)) {
                            Text(m.displayName, style = MaterialTheme.typography.titleSmall)
                            Text(m.roleName, style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
                        }
                        if (canManageRoles && m.userId != server.ownerUid) {
                            TextButton(onClick = { roleTargetMember = m }) { Text("Cargo") }
                        }
                        if (canManageMembers && m.userId != server.ownerUid && m.userId != currentUserId) {
                            TextButton(onClick = { onKickOrBan(m.userId, false, "Expulso por moderação") }) {
                                Text("Expulsar")
                            }
                            TextButton(onClick = { onKickOrBan(m.userId, true, "Banido por moderação") }) {
                                Text("Banir", color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Fechar") } }
    )

    roleTargetMember?.let { target ->
        AlertDialog(
            onDismissRequest = { roleTargetMember = null },
            title = { Text("Atribuir cargo a ${target.displayName}") },
            text = {
                Column {
                    roles.forEach { role ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onAssignRole(target.userId, role)
                                    roleTargetMember = null
                                }
                                .padding(vertical = 6.dp)
                        ) {
                            RadioButton(selected = target.roleId == role.id, onClick = {
                                onAssignRole(target.userId, role)
                                roleTargetMember = null
                            })
                            Text(role.name)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { roleTargetMember = null }) { Text("Cancelar") } }
        )
    }
}

@Composable
fun ModerationLogsDialog(
    logs: List<ModerationLog>,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Registros de Moderação") },
        text = {
            if (logs.isEmpty()) {
                Text("Nenhuma ação registrada.")
            } else {
                LazyColumn(modifier = Modifier.height(280.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(logs, key = { it.id }) { log ->
                        Column {
                            Text("${log.actorName} • ${log.action}", style = MaterialTheme.typography.titleSmall)
                            Text(log.details, style = MaterialTheme.typography.bodySmall)
                        }
                        Spacer(Modifier.height(4.dp))
                        HorizontalDivider()
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Fechar") } }
    )
}
