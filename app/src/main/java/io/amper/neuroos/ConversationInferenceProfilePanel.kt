package io.amper.neuroos

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import io.amper.neuroos.core.ConversationId
import io.amper.neuroos.core.ConversationInferenceProfile
import io.amper.neuroos.core.ConversationInferenceProfileStore
import io.amper.neuroos.core.ConversationReflectionMode
import io.amper.neuroos.core.SovereignConversationCoordinator
import io.amper.neuroos.core.TitanSessionRoutingPreference

/**
 * Explicit editor for the currently open conversation's sovereign inference metadata.
 *
 * Saving or clearing a profile never runs inference, prepares a model, invokes a tool or grants
 * authority. The next assistant/planning turn freezes the current profile before inference begins.
 */
@Composable
fun ConversationInferenceProfilePanel(
    conversations: SovereignConversationCoordinator,
    profiles: ConversationInferenceProfileStore,
    conversationId: ConversationId
) {
    var revision by remember(conversationId) { mutableIntStateOf(0) }
    val persisted = remember(conversationId, revision) { profiles.profile(conversationId) }
    val hasPersistedTurn = conversations.recent(conversationId, limit = 1).isNotEmpty()
    var editing by remember(conversationId) { mutableStateOf(false) }
    var contextDraft by remember(conversationId) { mutableStateOf("") }
    var outputDraft by remember(conversationId) { mutableStateOf("") }
    var temperatureDraft by remember(conversationId) { mutableStateOf("") }
    var sessionDraft by remember(conversationId) {
        mutableStateOf(TitanSessionRoutingPreference.STANDARD)
    }
    var reflectionDraft by remember(conversationId) {
        mutableStateOf(ConversationReflectionMode.STANDARD)
    }
    var status by remember(conversationId) { mutableStateOf("") }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Conversation Inference Profile", style = MaterialTheme.typography.titleMedium)
        if (!hasPersistedTurn) {
            Text("This new conversation will use production defaults until its first turn is persisted.")
            return@Column
        }

        Text("Prompt context budget: ${persisted?.maxPromptChars?.toString() ?: "inherit production default"} chars")
        Text("Max output tokens: ${persisted?.maxOutputTokens?.toString() ?: "inherit production default"}")
        Text("Temperature: ${persisted?.temperature?.toString() ?: "inherit production default"}")
        Text("Session routing: ${persisted?.sessionRoutingPreference?.name ?: TitanSessionRoutingPreference.STANDARD.name}")
        Text("Reflective answering: ${persisted?.reflectionMode?.name ?: ConversationReflectionMode.STANDARD.name}")
        Text("VERIFY may use one extra inference pass for tool-free answers; it never grants tool authority.")
        Text("Context fit, capabilities, backend health and resource admission remain authoritative.")

        if (editing) {
            OutlinedTextField(
                value = contextDraft,
                onValueChange = { contextDraft = it.take(5) },
                label = {
                    Text(
                        "Prompt chars ${ConversationInferenceProfile.MIN_PROMPT_CHARS}–${ConversationInferenceProfile.MAX_PROMPT_CHARS} · blank = inherit"
                    )
                }
            )
            OutlinedTextField(
                value = outputDraft,
                onValueChange = { outputDraft = it.take(8) },
                label = { Text("Max output tokens · blank = inherit") }
            )
            OutlinedTextField(
                value = temperatureDraft,
                onValueChange = { temperatureDraft = it.take(8) },
                label = { Text("Temperature 0.0–2.0 · blank = inherit") }
            )
            Text("Warm-session ranking policy")
            TitanSessionRoutingPreference.values().forEach { preference ->
                Button(onClick = { sessionDraft = preference }) {
                    Text(
                        when (preference) {
                            TitanSessionRoutingPreference.STANDARD ->
                                if (sessionDraft == preference) "✓ Standard" else "Standard"
                            TitanSessionRoutingPreference.PREFER_REUSE ->
                                if (sessionDraft == preference) "✓ Prefer session reuse" else "Prefer session reuse"
                            TitanSessionRoutingPreference.IGNORE_REUSE ->
                                if (sessionDraft == preference) "✓ Ignore reuse bonus" else "Ignore reuse bonus"
                        }
                    )
                }
            }
            Text("Final-answer verification")
            ConversationReflectionMode.values().forEach { mode ->
                Button(onClick = { reflectionDraft = mode }) {
                    Text(
                        when (mode) {
                            ConversationReflectionMode.STANDARD ->
                                if (reflectionDraft == mode) "✓ Standard one-pass answer" else "Standard one-pass answer"
                            ConversationReflectionMode.VERIFY ->
                                if (reflectionDraft == mode) "✓ Verify tool-free answers" else "Verify tool-free answers"
                        }
                    )
                }
            }
            Button(
                onClick = {
                    runCatching {
                        val contextChars = contextDraft.trim()
                            .takeIf { it.isNotEmpty() }
                            ?.toInt()
                        val outputTokens = outputDraft.trim()
                            .takeIf { it.isNotEmpty() }
                            ?.toInt()
                        val temperature = temperatureDraft.trim()
                            .takeIf { it.isNotEmpty() }
                            ?.toDouble()
                        val profile = ConversationInferenceProfile(
                            maxOutputTokens = outputTokens,
                            temperature = temperature,
                            sessionRoutingPreference = sessionDraft,
                            maxPromptChars = contextChars,
                            reflectionMode = reflectionDraft
                        )
                        profiles.put(conversationId, profile)
                        profile
                    }.fold(
                        onSuccess = { saved ->
                            editing = false
                            revision += 1
                            status = if (saved.isDefault) {
                                "Conversation inference profile cleared to production defaults"
                            } else {
                                "Conversation inference profile saved; it applies from the next inference turn"
                            }
                        },
                        onFailure = { error ->
                            status = "Inference profile update failed: ${error.message ?: error::class.java.simpleName}"
                        }
                    )
                }
            ) {
                Text("Save inference profile")
            }
            Button(
                onClick = {
                    editing = false
                    status = ""
                }
            ) {
                Text("Cancel profile edit")
            }
        } else {
            Button(
                onClick = {
                    contextDraft = persisted?.maxPromptChars?.toString().orEmpty()
                    outputDraft = persisted?.maxOutputTokens?.toString().orEmpty()
                    temperatureDraft = persisted?.temperature?.toString().orEmpty()
                    sessionDraft = persisted?.sessionRoutingPreference
                        ?: TitanSessionRoutingPreference.STANDARD
                    reflectionDraft = persisted?.reflectionMode ?: ConversationReflectionMode.STANDARD
                    status = ""
                    editing = true
                }
            ) {
                Text("Edit conversation inference profile")
            }
        }

        if (persisted != null) {
            Button(
                onClick = {
                    runCatching { profiles.clear(conversationId) }.fold(
                        onSuccess = {
                            editing = false
                            revision += 1
                            status = "Conversation inference profile cleared to production defaults"
                        },
                        onFailure = { error ->
                            status = "Inference profile clear failed: ${error.message ?: error::class.java.simpleName}"
                        }
                    )
                }
            ) {
                Text("Use production inference defaults")
            }
        }
        if (status.isNotBlank()) Text(status)
    }
}
