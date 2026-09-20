package io.amper.neuroos

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import io.amper.neuroos.core.ConversationId
import io.amper.neuroos.core.SovereignConversationCoordinator
import io.amper.neuroos.core.SovereignConversationTranscriptBrowser

/**
 * Browser for persisted sovereign conversation threads.
 *
 * Listing, searching, selecting and viewing a transcript never runs inference, proposes a tool
 * action, grants authority, or appends a conversation turn. Title, pin and fork controls are
 * explicit local metadata writes initiated by the user and never rewrite source turns.
 */
@Composable
fun SovereignConversationHistoryPanel(
    conversations: SovereignConversationCoordinator,
    activeConversationId: ConversationId,
    onOpen: (ConversationId) -> Unit
) {
    val transcriptBrowser = remember(conversations) { SovereignConversationTranscriptBrowser(conversations) }
    var searchQuery by remember(conversations) { mutableStateOf("") }
    var editingConversationId by remember(conversations) { mutableStateOf<ConversationId?>(null) }
    var viewingConversationId by remember(conversations) { mutableStateOf<ConversationId?>(null) }
    var titleDraft by remember(conversations) { mutableStateOf("") }
    var metadataStatus by remember(conversations) { mutableStateOf("") }
    val threads = if (searchQuery.isBlank()) {
        conversations.recentThreads(limit = 8)
    } else {
        conversations.searchThreads(searchQuery, limit = 8)
    }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Persistent Conversation History", style = MaterialTheme.typography.titleMedium)
        Text("Opening, searching or viewing transcripts does not run AMPER or invoke tools.")
        OutlinedTextField(
            value = searchQuery,
            onValueChange = {
                searchQuery = it.take(SovereignConversationCoordinator.MAX_THREAD_SEARCH_CHARS)
            },
            label = { Text("Search conversations") }
        )

        viewingConversationId?.let { transcriptConversationId ->
            Text("Conversation Transcript", style = MaterialTheme.typography.titleMedium)
            runCatching {
                transcriptBrowser.open(transcriptConversationId)
            }.fold(
                onSuccess = { transcript ->
                    Text(transcript.title ?: "Conversation ${transcript.conversationId.value.take(12)}")
                    transcript.forkInfo?.let { fork ->
                        Text(
                            "Forked from ${fork.sourceConversationId.value.take(12)} " +
                                "through persisted turn ${fork.throughTurnAtEpochMs}"
                        )
                    }
                    if (transcript.truncated) {
                        Text(
                            "Showing the latest ${SovereignConversationTranscriptBrowser.DEFAULT_TRANSCRIPT_TURNS} " +
                                "persisted turns"
                        )
                    } else {
                        Text("${transcript.turns.size} persisted turn(s)")
                    }
                    transcript.turns.forEach { turn ->
                        Text("${turn.role.name.lowercase()} · ${turn.createdAtEpochMs}")
                        Text(turn.text)
                        val route = listOfNotNull(turn.backendId, turn.modelId?.value).joinToString(" · ")
                        if (route.isNotBlank()) Text("Route: $route")
                        if (turn.selectedCapabilities.isNotEmpty()) {
                            Text(
                                "Capabilities: " + turn.selectedCapabilities
                                    .sortedBy { it.value }
                                    .joinToString(" · ") { it.value }
                            )
                        }
                        Button(
                            onClick = {
                                runCatching {
                                    conversations.forkConversation(
                                        sourceConversationId = transcript.conversationId,
                                        throughTurnAtEpochMs = turn.createdAtEpochMs
                                    )
                                }.fold(
                                    onSuccess = { forkedConversationId ->
                                        viewingConversationId = null
                                        metadataStatus =
                                            "Fork created at persisted turn ${turn.createdAtEpochMs}; source unchanged"
                                        onOpen(forkedConversationId)
                                    },
                                    onFailure = { error ->
                                        metadataStatus =
                                            "Conversation fork failed: ${error.message ?: error::class.java.simpleName}"
                                    }
                                )
                            }
                        ) {
                            Text("Fork from this turn")
                        }
                    }
                },
                onFailure = { error ->
                    Text("Transcript unavailable: ${error.message ?: error::class.java.simpleName}")
                }
            )
            Button(onClick = { viewingConversationId = null }) {
                Text("Close transcript")
            }
        }

        if (threads.isEmpty()) {
            Text(if (searchQuery.isBlank()) "No persisted conversation turns yet" else "No matching persisted conversations")
            if (metadataStatus.isNotBlank()) Text(metadataStatus)
            return@Column
        }

        threads.forEach { thread ->
            val current = thread.conversationId == activeConversationId
            val forkInfo = conversations.forkInfo(thread.conversationId)
            if (thread.pinned) Text("Pinned")
            if (thread.title != null) {
                Text("${thread.title} · ${thread.turnCount} turn(s)")
                Text("Started: ${thread.firstUserPreview}")
            } else {
                Text("${thread.firstUserPreview} · ${thread.turnCount} turn(s)")
            }
            forkInfo?.let { fork ->
                Text(
                    "Fork of ${fork.sourceConversationId.value.take(12)} " +
                        "through ${fork.throughTurnAtEpochMs}"
                )
            }
            Text("Latest ${thread.latestRole.name.lowercase()}: ${thread.latestTurnPreview}")
            val route = listOfNotNull(
                thread.latestAssistantBackendId,
                thread.latestAssistantModelId?.value
            ).joinToString(" · ")
            if (route.isNotBlank()) {
                Text("Last assistant route: $route")
            }
            if (thread.latestAssistantSelectedCapabilities.isNotEmpty()) {
                Text(
                    "Last capabilities: " + thread.latestAssistantSelectedCapabilities
                        .sortedBy { it.value }
                        .joinToString(" · ") { it.value }
                )
            }
            Text("Inference core: AMPER · single active AMI/AMNE foundation")

            Button(
                onClick = { viewingConversationId = thread.conversationId }
            ) {
                Text("View transcript")
            }
            Button(
                enabled = !current,
                onClick = { onOpen(thread.conversationId) }
            ) {
                Text(if (current) "Currently open" else "Open conversation")
            }
            Button(
                onClick = {
                    runCatching {
                        conversations.setPinned(thread.conversationId, !thread.pinned)
                    }.fold(
                        onSuccess = { pinned ->
                            metadataStatus = if (pinned) "Conversation pinned" else "Conversation unpinned"
                        },
                        onFailure = { error ->
                            metadataStatus = "Pin update failed: ${error.message ?: error::class.java.simpleName}"
                        }
                    )
                }
            ) {
                Text(if (thread.pinned) "Unpin conversation" else "Pin conversation")
            }

            if (editingConversationId == thread.conversationId) {
                OutlinedTextField(
                    value = titleDraft,
                    onValueChange = {
                        titleDraft = it.take(SovereignConversationCoordinator.MAX_THREAD_TITLE_CHARS)
                    },
                    label = { Text("Conversation title") }
                )
                Button(
                    enabled = titleDraft.isNotBlank(),
                    onClick = {
                        runCatching {
                            conversations.setTitle(thread.conversationId, titleDraft)
                        }.fold(
                            onSuccess = { saved ->
                                editingConversationId = null
                                titleDraft = ""
                                metadataStatus = "Saved conversation title: ${saved ?: "untitled"}"
                            },
                            onFailure = { error ->
                                metadataStatus = "Title update failed: ${error.message ?: error::class.java.simpleName}"
                            }
                        )
                    }
                ) {
                    Text("Save title")
                }
                if (thread.title != null) {
                    Button(
                        onClick = {
                            runCatching {
                                conversations.setTitle(thread.conversationId, null)
                            }.fold(
                                onSuccess = {
                                    editingConversationId = null
                                    titleDraft = ""
                                    metadataStatus = "Conversation title cleared"
                                },
                                onFailure = { error ->
                                    metadataStatus = "Title clear failed: ${error.message ?: error::class.java.simpleName}"
                                }
                            )
                        }
                    ) {
                        Text("Clear title")
                    }
                }
                Button(
                    onClick = {
                        editingConversationId = null
                        titleDraft = ""
                        metadataStatus = ""
                    }
                ) {
                    Text("Cancel title edit")
                }
            } else {
                Button(
                    onClick = {
                        editingConversationId = thread.conversationId
                        titleDraft = thread.title.orEmpty()
                        metadataStatus = ""
                    }
                ) {
                    Text(if (thread.title == null) "Set conversation title" else "Rename conversation")
                }
            }
        }
        if (metadataStatus.isNotBlank()) Text(metadataStatus)
    }
}
