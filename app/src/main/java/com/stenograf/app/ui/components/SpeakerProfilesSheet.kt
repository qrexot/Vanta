package com.stenograf.app.ui.components

import android.Manifest
import android.content.pm.PackageManager
import android.media.MediaPlayer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.RecordVoiceOver
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.stenograf.app.asr.VoiceprintMath
import com.stenograf.app.audio.VoiceSampleRecorder
import com.stenograf.app.data.model.SpeakerProfileEntity
import com.stenograf.app.i18n.LocalExtraStrings
import com.stenograf.app.i18n.LocalStrings
import com.stenograf.app.ui.theme.LocalStenografColors
import com.stenograf.app.ui.theme.Radius
import com.stenograf.app.ui.theme.Spacing
import com.stenograf.app.ui.theme.VantaType
import com.stenograf.app.ui.theme.rememberVantaHaptics
import com.stenograf.app.ui.theme.vantaSurfaceCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

private val TrainingEmerald = Color(0xFF10B981)

/**
 * Full voiceprint manager: guided multi-phrase voice calibration, live sample clip playback,
 * transcript context preview, 1-tap assignment to known people, and number/role tagging.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SpeakerProfilesSheet(
    profiles: List<SpeakerProfileEntity>,
    useNeuralDenoiser: Boolean,
    isRecordingActive: Boolean,
    onDismiss: () -> Unit,
    onEnrollPcmSample: suspend (name: String, tagOrPhone: String, pcm16kBytes: ByteArray, existingProfileId: Long?) -> Boolean,
    onUpdateProfileDetails: (profileId: Long, newName: String, tagOrPhone: String, renameInRecordings: Boolean) -> Unit,
    onMergeProfiles: (targetId: Long, sourceId: Long) -> Unit,
    onEnsureSampleClip: suspend (profileId: Long) -> SpeakerProfileEntity?,
    onOpenRecording: (recordingId: Long) -> Unit,
    onDeleteProfile: (Long) -> Unit,
    onShowMessage: (String) -> Unit
) {
    val colors = LocalStenografColors.current
    val s = LocalStrings.current
    val extra = LocalExtraStrings.current
    val context = LocalContext.current
    val haptics = rememberVantaHaptics()
    val scope = rememberCoroutineScope()

    // Guided calibration capture state
    var showMicEnrollCard by remember { mutableStateOf(false) }
    var reinforceTargetId by remember { mutableStateOf<Long?>(null) }
    var micEnrollName by remember { mutableStateOf("") }
    var micEnrollTag by remember { mutableStateOf("") }
    var isCapturingSample by remember { mutableStateOf(false) }
    var captureLevel by remember { mutableFloatStateOf(0f) }
    var captureElapsedMs by remember { mutableLongStateOf(0L) }
    var captureActiveSpeechMs by remember { mutableLongStateOf(0L) }
    var captureSpeakingNow by remember { mutableStateOf(false) }
    val stopSignal = remember { AtomicBoolean(false) }
    var captureJob by remember { mutableStateOf<Job?>(null) }

    // Lightweight inline preview player for saved profile clips
    var samplePlayer by remember { mutableStateOf<MediaPlayer?>(null) }
    var playingProfileId by remember { mutableStateOf<Long?>(null) }
    var loadingClipProfileId by remember { mutableStateOf<Long?>(null) }

    // Edit / Assign modal state
    var editTargetId by remember { mutableStateOf<Long?>(null) }
    val liveEditTarget = editTargetId?.let { id -> profiles.firstOrNull { it.id == id } }
    var renameInput by remember { mutableStateOf("") }
    var tagInput by remember { mutableStateOf("") }
    var renameInPast by remember { mutableStateOf(true) }

    fun stopClipPlayback() {
        playingProfileId = null
        val active = samplePlayer
        samplePlayer = null
        if (active != null) {
            runCatching { if (active.isPlaying) active.stop() }
            runCatching { active.release() }
        }
    }

    fun toggleClipPlayback(profile: SpeakerProfileEntity) {
        if (playingProfileId == profile.id) {
            haptics.tick()
            stopClipPlayback()
            return
        }
        stopClipPlayback()
        loadingClipProfileId = profile.id
        scope.launch {
            val resolved = withContext(Dispatchers.IO) { onEnsureSampleClip(profile.id) } ?: profile
            loadingClipProfileId = null
            val path = resolved.sampleAudioPath
            val clipFile = if (path.isNotBlank()) File(path) else null
            if (clipFile == null || !clipFile.exists() || clipFile.length() <= 44L) {
                onShowMessage(extra.noVoiceSampleYet)
                return@launch
            }
            try {
                val mp = MediaPlayer().apply {
                    setDataSource(clipFile.absolutePath)
                    setOnCompletionListener {
                        if (samplePlayer === it) {
                            stopClipPlayback()
                        } else {
                            runCatching { it.release() }
                        }
                    }
                    setOnErrorListener { failed, _, _ ->
                        if (samplePlayer === failed) {
                            stopClipPlayback()
                        } else {
                            runCatching { failed.release() }
                        }
                        true
                    }
                    prepare()
                    start()
                }
                samplePlayer = mp
                playingProfileId = profile.id
                haptics.tick()
            } catch (_: Throwable) {
                stopClipPlayback()
                onShowMessage(extra.noVoiceSampleYet)
            }
        }
    }

    LaunchedEffect(editTargetId) {
        val targetId = editTargetId ?: return@LaunchedEffect
        onEnsureSampleClip(targetId)
    }

    DisposableEffect(Unit) {
        onDispose {
            stopSignal.set(true)
            captureJob?.cancel()
            stopClipPlayback()
        }
    }

    fun startMicCapture() {
        if (isRecordingActive) {
            onShowMessage(s.activeRecordingBanner)
            return
        }
        stopClipPlayback()
        val cleanName = micEnrollName.trim()
        if (reinforceTargetId == null && cleanName.isEmpty()) {
            onShowMessage(extra.requiredFieldMessage)
            return
        }
        stopSignal.set(false)
        isCapturingSample = true
        captureLevel = 0.06f
        captureElapsedMs = 0L
        captureActiveSpeechMs = 0L
        captureSpeakingNow = false
        captureJob = scope.launch(Dispatchers.IO) {
            val result = VoiceSampleRecorder.captureSmartSamplePcm16(
                assets = context.assets,
                useNeuralDenoiser = useNeuralDenoiser,
                maxDurationMs = 12_000L,
                shouldStop = { stopSignal.get() },
                onProgress = { progress ->
                    captureLevel = progress.level
                    captureElapsedMs = progress.elapsedMs
                    captureActiveSpeechMs = progress.activeSpeechMs
                    captureSpeakingNow = progress.isSpeakingNow
                }
            )
            val saved = if (result.pcm16kBytes.isNotEmpty()) {
                onEnrollPcmSample(cleanName, micEnrollTag.trim(), result.pcm16kBytes, reinforceTargetId)
            } else {
                false
            }
            withContext(Dispatchers.Main) {
                isCapturingSample = false
                if (saved) {
                    haptics.confirm()
                    micEnrollName = ""
                    micEnrollTag = ""
                    reinforceTargetId = null
                    showMicEnrollCard = false
                    onShowMessage(extra.voiceTrainedSuccess)
                } else {
                    onShowMessage(extra.insufficientSpeechSampleError)
                }
            }
        }
    }

    val micPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startMicCapture() else onShowMessage(s.micPermissionRequired)
    }

    fun launchCaptureWithPermissionCheck() {
        val hasPerm = ContextCompat.checkSelfPermission(
            context, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (hasPerm) startMicCapture()
        else micPermLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    VantaBottomSheet(
        onDismissRequest = {
            stopSignal.set(true)
            stopClipPlayback()
            onDismiss()
        },
        title = extra.speakerProfilesTitle,
        subtitle = extra.speakerProfilesSubtitle,
        headerIcon = Icons.Rounded.RecordVoiceOver
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            if (!showMicEnrollCard) {
                VantaPrimaryButton(
                    label = extra.enrollFromMicAction,
                    icon = Icons.Rounded.Mic,
                    onClick = {
                        haptics.click()
                        reinforceTargetId = null
                        micEnrollName = ""
                        micEnrollTag = ""
                        showMicEnrollCard = true
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            } else {
                val targetName = reinforceTargetId?.let { id -> profiles.firstOrNull { it.id == id }?.name }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .vantaSurfaceCard(colors, radius = Radius.md, elevated = false)
                        .padding(Spacing.md),
                    verticalArrangement = Arrangement.spacedBy(Spacing.sm)
                ) {
                    if (targetName != null) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
                        ) {
                            Text(
                                text = extra.reinforceVoiceAction + ":",
                                color = colors.textSecondary,
                                style = VantaType.bodySmall
                            )
                            SpeakerPill(speakerKey = targetName, label = targetName)
                        }
                    } else {
                        VantaTextField(
                            value = micEnrollName,
                            onValueChange = { if (!isCapturingSample) micEnrollName = it },
                            label = extra.newSpeakerNameHint,
                            singleLine = true,
                            required = true
                        )
                        VantaTextField(
                            value = micEnrollTag,
                            onValueChange = { if (!isCapturingSample) micEnrollTag = it },
                            label = extra.tagOrPhoneHint,
                            singleLine = true
                        )
                    }

                    Text(
                        text = extra.reinforceCalibrationTips,
                        color = colors.textSecondary,
                        style = VantaType.bodySmall
                    )

                    if (isCapturingSample) {
                        VantaLinearProgressTrack(
                            progress = (captureElapsedMs / 12_000f).coerceIn(0.04f, 1f),
                            fillColor = if (captureSpeakingNow) TrainingEmerald else colors.accent
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(if (captureSpeakingNow) TrainingEmerald else colors.textMuted)
                                )
                                Text(
                                    text = if (captureSpeakingNow) extra.speakingNowLabel else extra.waitingSpeechLabel,
                                    color = if (captureSpeakingNow) TrainingEmerald else colors.textMuted,
                                    style = VantaType.bodySmall
                                )
                            }
                            Text(
                                text = "${extra.activeSpeechCounterLabel}: ${String.format(Locale.US, "%.1f", captureActiveSpeechMs / 1000f)} / 12s",
                                color = colors.accent,
                                style = VantaType.monoBadge
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                            VantaTonalButton(
                                label = s.cancel,
                                onClick = {
                                    stopSignal.set(true)
                                    captureJob?.cancel()
                                    isCapturingSample = false
                                    reinforceTargetId = null
                                    showMicEnrollCard = false
                                },
                                modifier = Modifier.weight(1f)
                            )
                            VantaPrimaryButton(
                                label = extra.stopAndSaveAction,
                                icon = Icons.Rounded.Stop,
                                enabled = captureActiveSpeechMs >= 1_200L || captureElapsedMs >= 2_500L,
                                onClick = { stopSignal.set(true) },
                                modifier = Modifier.weight(1.4f)
                            )
                        }
                    } else {
                        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                            VantaTonalButton(
                                label = s.cancel,
                                onClick = {
                                    reinforceTargetId = null
                                    showMicEnrollCard = false
                                },
                                modifier = Modifier.weight(1f)
                            )
                            VantaPrimaryButton(
                                label = s.startAction,
                                icon = Icons.Rounded.Mic,
                                enabled = reinforceTargetId != null || micEnrollName.trim().isNotEmpty(),
                                onClick = { launchCaptureWithPermissionCheck() },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }

            if (profiles.isEmpty()) {
                EmptyStateView(
                    icon = Icons.Rounded.RecordVoiceOver,
                    title = extra.noSpeakerProfilesTitle,
                    subtitle = extra.noSpeakerProfilesSubtitle
                )
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 400.dp),
                    verticalArrangement = Arrangement.spacedBy(Spacing.xs)
                ) {
                    items(profiles, key = { it.id }) { profile ->
                        val protoCount = remember(profile.prototypesJson) {
                            VoiceprintMath.decodeEmbedding(profile.embeddingJson)?.let {
                                VoiceprintMath.decodePrototypes(profile.prototypesJson, it).size
                            } ?: 1
                        }
                        val strength = remember(profile.sampleCount, profile.speechSeconds, protoCount) {
                            VoiceprintMath.trainingProgress(profile.sampleCount, profile.speechSeconds, protoCount)
                        }
                        val tier = remember(profile.sampleCount, profile.speechSeconds, protoCount) {
                            VoiceprintMath.trainingTier(profile.sampleCount, profile.speechSeconds, protoCount)
                        }
                        val tierLabel = when (tier) {
                            3 -> extra.trainingTierReference
                            2 -> extra.trainingTierConfident
                            else -> extra.trainingTierInitial
                        }
                        val tierColor = when (tier) {
                            3 -> TrainingEmerald
                            2 -> colors.accent
                            else -> colors.textSecondary
                        }
                        val isPlaying = playingProfileId == profile.id
                        val isLoadingClip = loadingClipProfileId == profile.id
                        val effectiveSecs = if (profile.speechSeconds > 0f) profile.speechSeconds else profile.sampleCount * 2.5f

                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .vantaSurfaceCard(colors, radius = Radius.md, elevated = false)
                                .padding(horizontal = Spacing.md, vertical = Spacing.sm),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
                            ) {
                                SpeakerPill(speakerKey = profile.name, label = profile.name)
                                if (profile.tagOrPhone.isNotBlank()) {
                                    StatusPill(
                                        label = profile.tagOrPhone,
                                        containerColor = colors.surfaceRecessed,
                                        contentColor = colors.textPrimary
                                    )
                                }
                                Spacer(Modifier.weight(1f))
                                StatusPill(
                                    label = when {
                                        isLoadingClip -> "…"
                                        isPlaying -> extra.stopVoicePreviewAction
                                        else -> "▶ ${extra.listenVoiceAction}"
                                    },
                                    containerColor = if (isPlaying) colors.accentSoft else colors.surfaceRecessed,
                                    contentColor = if (isPlaying) colors.accent else colors.textPrimary,
                                    onClick = { toggleClipPlayback(profile) }
                                )
                            }

                            if (profile.sampleTranscript.isNotBlank() || profile.sampleSourceTitle.isNotBlank()) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(Radius.sm))
                                        .background(colors.surfaceRecessed)
                                        .padding(horizontal = Spacing.sm, vertical = 6.dp),
                                    verticalArrangement = Arrangement.spacedBy(2.dp)
                                ) {
                                    if (profile.sampleSourceTitle.isNotBlank()) {
                                        Text(
                                            text = profile.sampleSourceTitle,
                                            color = colors.textMuted,
                                            style = VantaType.monoBadge,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    if (profile.sampleTranscript.isNotBlank()) {
                                        Text(
                                            text = "«${profile.sampleTranscript}»",
                                            color = colors.textSecondary,
                                            style = VantaType.bodySmall,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            } else {
                                Text(
                                    text = extra.noVoiceSampleYet,
                                    color = colors.textMuted,
                                    style = VantaType.bodySmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }

                            VantaLinearProgressTrack(progress = strength, fillColor = tierColor)

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    StatusPill(
                                        label = "$tierLabel ${(strength * 100).toInt()}%",
                                        containerColor = colors.surfaceRecessed,
                                        contentColor = tierColor
                                    )
                                    Text(
                                        text = "×${profile.sampleCount} · ${effectiveSecs.toInt()}s · ${protoCount}v",
                                        color = colors.textMuted,
                                        style = VantaType.monoBadge
                                    )
                                }
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    StatusPill(
                                        label = extra.reinforceVoiceAction,
                                        containerColor = colors.accentSoft,
                                        contentColor = colors.accent,
                                        onClick = {
                                            haptics.click()
                                            reinforceTargetId = profile.id
                                            micEnrollName = profile.name
                                            micEnrollTag = profile.tagOrPhone
                                            showMicEnrollCard = true
                                        }
                                    )
                                    VantaIconButton(
                                        icon = Icons.Rounded.Edit,
                                        contentDescription = s.rename,
                                        tint = colors.textSecondary,
                                        onClick = {
                                            haptics.tick()
                                            editTargetId = profile.id
                                            renameInput = profile.name
                                            tagInput = profile.tagOrPhone
                                            renameInPast = true
                                        }
                                    )
                                    VantaIconButton(
                                        icon = Icons.Rounded.DeleteOutline,
                                        contentDescription = s.delete,
                                        tint = colors.destructive,
                                        onClick = {
                                            haptics.heavy()
                                            if (playingProfileId == profile.id) stopClipPlayback()
                                            onDeleteProfile(profile.id)
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    liveEditTarget?.let { target ->
        val candidates = remember(profiles, target.id) {
            profiles.filter { it.id != target.id }
        }
        val isPlayingTarget = playingProfileId == target.id

        VantaBottomSheet(
            onDismissRequest = { editTargetId = null },
            title = s.rename,
            subtitle = target.name,
            headerIcon = Icons.Rounded.Edit
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                // Playable preview & source recording context so the user knows whose voice this is
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .vantaSurfaceCard(colors, radius = Radius.md, elevated = false)
                        .padding(Spacing.md),
                    verticalArrangement = Arrangement.spacedBy(Spacing.xs)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
                    ) {
                        VantaTonalButton(
                            label = if (isPlayingTarget) extra.stopVoicePreviewAction else extra.listenVoiceAction,
                            icon = if (isPlayingTarget) Icons.Rounded.Stop else Icons.Rounded.PlayArrow,
                            onClick = { toggleClipPlayback(target) },
                            modifier = Modifier.weight(1f)
                        )
                        target.sampleRecordingId?.let { recId ->
                            VantaTonalButton(
                                label = extra.openSourceRecordingAction,
                                icon = Icons.AutoMirrored.Rounded.OpenInNew,
                                onClick = {
                                    haptics.click()
                                    stopClipPlayback()
                                    editTargetId = null
                                    onDismiss()
                                    onOpenRecording(recId)
                                },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                    if (target.sampleSourceTitle.isNotBlank()) {
                        Text(
                            text = target.sampleSourceTitle,
                            color = colors.textMuted,
                            style = VantaType.monoBadge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    if (target.sampleTranscript.isNotBlank()) {
                        Text(
                            text = "«${target.sampleTranscript}»",
                            color = colors.textSecondary,
                            style = VantaType.bodySmall,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                // 1-tap assign/merge into any known saved person
                if (candidates.isNotEmpty()) {
                    SectionHeader(extra.assignToPersonLabel)
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
                        verticalArrangement = Arrangement.spacedBy(Spacing.xs)
                    ) {
                        candidates.forEach { person ->
                            val chipLabel = if (person.tagOrPhone.isNotBlank()) {
                                "${person.name} (${person.tagOrPhone})"
                            } else person.name
                            StatusPill(
                                label = chipLabel,
                                containerColor = colors.surfaceRecessed,
                                contentColor = colors.textPrimary,
                                onClick = {
                                    haptics.confirm()
                                    if (playingProfileId == target.id) stopClipPlayback()
                                    onMergeProfiles(person.id, target.id)
                                    editTargetId = null
                                }
                            )
                        }
                    }
                }

                VantaTextField(
                    value = renameInput,
                    onValueChange = { renameInput = it },
                    label = extra.newSpeakerNameHint,
                    required = true,
                    singleLine = true
                )
                VantaTextField(
                    value = tagInput,
                    onValueChange = { tagInput = it },
                    label = extra.tagOrPhoneHint,
                    singleLine = true
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(Radius.sm))
                        .clickable { renameInPast = !renameInPast }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
                ) {
                    VantaSelectionMark(selected = renameInPast)
                    Text(
                        text = extra.renameInAllRecordingsOption,
                        color = colors.textSecondary,
                        style = VantaType.bodySmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    VantaTonalButton(
                        label = extra.reinforceVoiceAction,
                        icon = Icons.Rounded.Mic,
                        onClick = {
                            haptics.click()
                            editTargetId = null
                            reinforceTargetId = target.id
                            micEnrollName = renameInput.trim().ifEmpty { target.name }
                            micEnrollTag = tagInput.trim()
                            showMicEnrollCard = true
                        },
                        modifier = Modifier.weight(1f)
                    )
                    VantaPrimaryButton(
                        label = s.save,
                        enabled = renameInput.trim().isNotEmpty(),
                        onClick = {
                            haptics.confirm()
                            onUpdateProfileDetails(target.id, renameInput.trim(), tagInput.trim(), renameInPast)
                            editTargetId = null
                        },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}
