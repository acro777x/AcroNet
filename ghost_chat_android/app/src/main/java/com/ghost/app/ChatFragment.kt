package com.ghost.app

import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.getSystemService
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import android.Manifest
import android.content.pm.PackageManager
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import java.io.File
import com.ghost.app.databinding.FragmentChatBinding
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * ChatFragment — MVVM View Layer (Final Version)
 *
 * Integrates ALL phases:
 * Phase 1: Long-press actions + multi-select
 * Phase 4: In-chat search with highlighting
 * Phase 5: Read receipts + disappearing messages
 * Phase 6: Swipe-to-reply
 */
class ChatFragment : Fragment() {

    private var _binding: FragmentChatBinding? = null
    private val binding get() = _binding!!

    private lateinit var adapter: MessageAdapter
    private var replyToPosition: String = ""
    private var isSearchMode = false

    private var mediaRecorder: MediaRecorder? = null
    private var mediaPlayer: android.media.MediaPlayer? = null
    private var audioFile: File? = null

    private val requestAudioPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) Toast.makeText(requireContext(), "Mic enabled", Toast.LENGTH_SHORT).show()
    }

    private val viewModel: ChatViewModel by viewModels {
        val args = requireArguments()
        ChatViewModel.Factory(
            alias = args.getString("alias")!!,
            key = args.getString("key")!!,
            tok = args.getString("tok")!!,
            roomHash = args.getString("roomHash")!!,
            aesKey = GhostCrypto.deriveKey(args.getString("key")!!)
        )
    }

    private val pickImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let {
            viewModel.sendImage(requireContext().cacheDir, it, requireContext().contentResolver)
            Toast.makeText(requireContext(), "🔒 Uploading encrypted image...", Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        fun newInstance(result: LoginResult): ChatFragment {
            return ChatFragment().apply {
                arguments = Bundle().apply {
                    putString("alias", result.alias)
                    putString("key", result.key)
                    putString("tok", result.token)
                    putString("roomHash", result.roomHash)
                }
            }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentChatBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // ── RecyclerView Setup ──────────────────────────────────────
        adapter = MessageAdapter(
            onImageClick = { url ->
                // Basic full-screen image viewer intent could go here
            },
            onAudioClick = { filename ->
                playAudioMemo(filename)
            },
            onLongClick = { msg -> showMessageActions(msg) },
            onBurnRevealed = { msgId -> viewModel.deleteForEveryone(msgId) }
        )
        val lm = LinearLayoutManager(requireContext())
        lm.stackFromEnd = true
        binding.rvMessages.layoutManager = lm
        binding.rvMessages.adapter = adapter
        binding.rvMessages.itemAnimator = ChatItemAnimator()

        // Swipe-to-Reply
        val swipeCallback = SwipeReplyCallback { position ->
            val msg = adapter.currentList.getOrNull(position) ?: return@SwipeReplyCallback
            showReplyBar(msg)
            hapticPulse()
        }
        ItemTouchHelper(swipeCallback).attachToRecyclerView(binding.rvMessages)

        binding.tvRoomName.text = viewModel.roomDisplayName

        // Pull-to-refresh
        binding.swipeRefresh.setColorSchemeColors(0xFF3b82f6.toInt())
        binding.swipeRefresh.setProgressBackgroundColorSchemeColor(0xFF0f172a.toInt())
        binding.swipeRefresh.setOnRefreshListener {
            viewModel.fetchMessagesOnce()
            binding.swipeRefresh.isRefreshing = false
        }

        // Send button with scale animation
        binding.btnSend.setOnClickListener {
            val text = binding.etMessage.text.toString().trim()
            if (text.isNotEmpty()) {
                // Pulse animation
                binding.btnSend.animate().scaleX(0.8f).scaleY(0.8f).setDuration(80).withEndAction {
                    binding.btnSend.animate().scaleX(1f).scaleY(1f).setDuration(80).start()
                }.start()
                hapticPulse()
                
                // Build Payload
                val payload = if (replyToPosition.isNotEmpty()) {
                    val preview = binding.tvReplyPreview.text.toString()
                    val sender = binding.tvReplyName.text.toString().removePrefix("↩ Replying to ")
                    "REPLY:$replyToPosition|$sender|$preview§$text"
                } else {
                    text
                }
                
                viewModel.sendMessage(payload)
                binding.etMessage.setText("")
                hideReplyBar()
            }
        }

        // Cancel Reply Button
        binding.btnCancelReply?.setOnClickListener {
            hideReplyBar()
        }

        // Enter sends
        binding.etMessage.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                binding.btnSend.performClick(); true
            } else false
        }

        // Typing detection
        binding.etMessage.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (!s.isNullOrEmpty()) viewModel.sendTyping()
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        // Audio Recording — TAP TOGGLE (tap to start, tap again to send)
        var isRecording = false
        var recordingTimerJob: Job? = null
        var recordingStartTime = 0L

        binding.btnMic.setOnClickListener {
            if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                requestAudioPermission.launch(Manifest.permission.RECORD_AUDIO)
                return@setOnClickListener
            }

            if (!isRecording) {
                // START recording
                startRecording()
                isRecording = true
                recordingStartTime = System.currentTimeMillis()
                binding.btnMic.setColorFilter(android.graphics.Color.RED)
                binding.recordingBar.visibility = View.VISIBLE

                // Pulsing dot animation
                binding.vRecordingDot.animate()
                    .alpha(0.3f).setDuration(500).withEndAction {
                        binding.vRecordingDot.animate().alpha(1f).setDuration(500).start()
                    }.start()

                // Timer updater
                recordingTimerJob = viewLifecycleOwner.lifecycleScope.launch {
                    while (true) {
                        val elapsed = (System.currentTimeMillis() - recordingStartTime) / 1000
                        val mins = elapsed / 60
                        val secs = elapsed % 60
                        binding.tvRecordingTimer.text = "Recording ${mins}:${String.format("%02d", secs)}"
                        delay(500)
                    }
                }
            } else {
                // STOP and send
                isRecording = false
                recordingTimerJob?.cancel()
                binding.btnMic.setColorFilter(ContextCompat.getColor(requireContext(), R.color.text_secondary))
                binding.recordingBar.visibility = View.GONE

                val elapsed = System.currentTimeMillis() - recordingStartTime
                if (elapsed < 1000) {
                    // Too short — discard
                    cancelRecording()
                    Toast.makeText(requireContext(), "Too short — hold for at least 1 second", Toast.LENGTH_SHORT).show()
                } else {
                    stopRecordingAndSend()
                }
            }
        }

        // Cancel recording button
        binding.btnCancelRecording.setOnClickListener {
            isRecording = false
            recordingTimerJob?.cancel()
            binding.btnMic.setColorFilter(ContextCompat.getColor(requireContext(), R.color.text_secondary))
            binding.recordingBar.visibility = View.GONE
            cancelRecording()
            Toast.makeText(requireContext(), "Recording cancelled", Toast.LENGTH_SHORT).show()
        }

        // Attach
        binding.btnAttach.setOnClickListener { pickImage.launch("image/*") }

        // Back / leave
        binding.btnBack.setOnClickListener {
            viewModel.leaveRoom()
            (activity as? MainActivity)?.showLogin()
        }

        // Cancel reply
        binding.btnCancelReply.setOnClickListener { hideReplyBar() }

        // Settings gear
        binding.btnSettings.setOnClickListener {
            (activity as? MainActivity)?.showSettings()
        }

        // Radar map with slide-up animation
        binding.btnRadar.setOnClickListener {
            parentFragmentManager.beginTransaction()
                .setCustomAnimations(R.anim.slide_up_in, R.anim.slide_out_left,
                    R.anim.scale_fade_in, R.anim.slide_down_out)
                .replace(R.id.root_container, RadarFragment())
                .addToBackStack(null)
                .commit()
        }

        // ── Observe ALL StateFlows ──────────────────────────────────
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {

                // Messages (auto-pauses in background)
                launch {
                    viewModel.messageUpdates.collect { messages ->
                        // Phase 5: Filter expired messages
                        val visible = messages.filter { !it.isExpired() }
                        adapter.submitList(visible.toList()) {
                            if (visible.isNotEmpty()) {
                                binding.rvMessages.smoothScrollToPosition(visible.size - 1)
                            }
                        }
                    }
                }

                // Connection banner with slide animation
                launch {
                    viewModel.connectionState.collect { state ->
                        if (state == ConnectionState.DISCONNECTED) {
                            binding.tvConnectionBanner.visibility = View.VISIBLE
                            binding.tvConnectionBanner.translationY = -40f
                            binding.tvConnectionBanner.animate().translationY(0f).setDuration(300).start()
                        } else {
                            binding.tvConnectionBanner.animate().translationY(-40f).setDuration(200).withEndAction {
                                binding.tvConnectionBanner.visibility = View.GONE
                            }.start()
                        }
                        val dotColor = if (state == ConnectionState.CONNECTED) 0xFF10b981.toInt() else 0xFFef4444.toInt()
                        binding.onlineDot.background.setTint(dotColor)
                        // Online dot pulse when connected
                        if (state == ConnectionState.CONNECTED) {
                            binding.onlineDot.animate().scaleX(1.3f).scaleY(1.3f).setDuration(400).withEndAction {
                                binding.onlineDot.animate().scaleX(1f).scaleY(1f).setDuration(400).start()
                            }.start()
                        }
                    }
                }

                // Status text & Typing indicator with animated dots
                launch {
                    viewModel.typingUser.collect { typingName ->
                        if (typingName.isNotEmpty()) {
                            binding.tvTypingIndicator.visibility = View.VISIBLE
                            // Fade-in
                            binding.tvTypingIndicator.alpha = 0f
                            binding.tvTypingIndicator.animate().alpha(1f).setDuration(200).start()
                            // Animated dots cycle
                            val baseText = "$typingName is typing"
                            viewLifecycleOwner.lifecycleScope.launch {
                                var dots = 0
                                while (viewModel.typingUser.value.isNotEmpty()) {
                                    dots = (dots + 1) % 4
                                    binding.tvTypingIndicator.text = "$baseText${".".repeat(dots)}"
                                    delay(400)
                                }
                            }
                        } else {
                            // Fade-out
                            binding.tvTypingIndicator.animate().alpha(0f).setDuration(150).withEndAction {
                                binding.tvTypingIndicator.visibility = View.GONE
                            }.start()
                        }
                    }
                }

                launch {
                    viewModel.onlineCount.collect { count ->
                        updateStatus(count, viewModel.loraStatus.value)
                    }
                }
                launch {
                    viewModel.loraUpdates.collect { lora ->
                        updateStatus(viewModel.onlineCount.value, lora)
                    }
                }

                // Send feedback
                launch {
                    viewModel.sendState.collect { state ->
                        when (state) {
                            is SendState.Error -> Toast.makeText(requireContext(), state.message, Toast.LENGTH_SHORT).show()
                            is SendState.Sent -> hapticPulse()
                            else -> {}
                        }
                    }
                }
            }
        }
    }

    // ── Phase 1: Message Actions ────────────────────────────────────
    private fun showMessageActions(msg: ChatMessage) {
        hapticPulse()
        val sheet = MessageActionSheet(
            message = msg,
            onAction = { action ->
                when (action) {
                    MessageActionSheet.Action.COPY -> { /* clipboard handled in sheet */ }
                    MessageActionSheet.Action.REPLY -> showReplyBar(msg)
                    MessageActionSheet.Action.STAR -> {
                        SecurePrefs.addStarredMsg(msg.id)
                        Toast.makeText(requireContext(), "⭐ Message starred", Toast.LENGTH_SHORT).show()
                    }
                    MessageActionSheet.Action.UNSTAR -> {
                        SecurePrefs.removeStarredMsg(msg.id)
                        Toast.makeText(requireContext(), "Unstarred", Toast.LENGTH_SHORT).show()
                    }
                    MessageActionSheet.Action.DELETE_LOCAL -> {
                        viewModel.deleteMessageLocal(msg.id)
                        Toast.makeText(requireContext(), "🗑 Deleted", Toast.LENGTH_SHORT).show()
                    }
                    MessageActionSheet.Action.DELETE_EVERYONE -> {
                        viewModel.deleteForEveryone(msg.id)
                        Toast.makeText(requireContext(), "💣 Delete command sent", Toast.LENGTH_SHORT).show()
                    }
                    MessageActionSheet.Action.FORWARD -> {
                        // Copy message content to clipboard for forwarding
                        val clipboard = requireContext().getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        val content = if (msg.isImage) "IMG:${msg.content}" else msg.content
                        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Ghost Forward", content))
                        Toast.makeText(requireContext(), "📤 Message copied — paste in another room", Toast.LENGTH_SHORT).show()
                    }
                }
            },
            onReact = { emoji ->
                // Send reaction as protocol message
                viewModel.sendMessage("REACT:${msg.id}§$emoji")
                Toast.makeText(requireContext(), "$emoji Reaction sent", Toast.LENGTH_SHORT).show()
            }
        )
        sheet.show(childFragmentManager, "actions")
    }

    // ── Reply Bar ───────────────────────────────────────────────────
    private fun showReplyBar(msg: ChatMessage) {
        replyToPosition = msg.id
        binding.replyBar.visibility = View.VISIBLE
        // Slide-up animation
        binding.replyBar.translationY = 60f
        binding.replyBar.alpha = 0f
        binding.replyBar.animate().translationY(0f).alpha(1f).setDuration(200).start()
        binding.tvReplyName.text = "↩ Replying to ${msg.sender}"
        binding.tvReplyPreview.text = if (msg.isImage) "📷 Photo" else msg.content.take(60)
    }

    private fun hideReplyBar() {
        replyToPosition = ""
        binding.replyBar.animate().translationY(60f).alpha(0f).setDuration(200).withEndAction {
            binding.replyBar.visibility = View.GONE
        }.start()
    }

    private fun stopSearch() {
        isSearchMode = false
        adapter.searchQuery = ""
    }

    private fun startRecording() {
        hapticPulse()
        val file = File(requireContext().cacheDir, "vox_${System.currentTimeMillis()}.m4a")
        audioFile = file
        mediaRecorder = MediaRecorder(requireContext()).apply {
            // VOICE_COMMUNICATION applies built-in noise suppression + echo cancellation
            setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setAudioEncodingBitRate(128000)   // 128kbps for clear voice
            setAudioSamplingRate(44100)       // CD quality
            setOutputFile(file.absolutePath)
            try {
                prepare()
                start()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun cancelRecording() {
        try {
            mediaRecorder?.stop()
            mediaRecorder?.release()
            mediaRecorder = null
            audioFile?.delete()
            audioFile = null
        } catch (e: Exception) {
            e.printStackTrace()
            mediaRecorder?.release()
            mediaRecorder = null
        }
    }

    private fun stopRecordingAndSend() {
        try {
            mediaRecorder?.stop()
            mediaRecorder?.release()
            mediaRecorder = null
            audioFile?.let { file ->
                if (file.exists() && file.length() > 0) {
                    Toast.makeText(requireContext(), "🎤 Sending Voice Memo...", Toast.LENGTH_SHORT).show()
                    viewModel.sendVoiceMemo(file)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun playAudioMemo(filename: String) {
        try {
            mediaPlayer?.release()
            val url = GhostApi.downloadUrl(filename)
            mediaPlayer = android.media.MediaPlayer().apply {
                setDataSource(url)
                prepareAsync()
                setOnPreparedListener { 
                    it.start()
                    Toast.makeText(requireContext(), "Playing Audio...", Toast.LENGTH_SHORT).show()
                }
                setOnCompletionListener {
                    it.release()
                    mediaPlayer = null
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(requireContext(), "Failed to play audio", Toast.LENGTH_SHORT).show()
        }
    }

    // Duplicate hideReplyBar removed

    private fun updateStatus(online: String, lora: String) {
        if (!isAdded) return
        binding.tvRoomStatus.text = "🔒 AES-256  •  LoRa: $lora  •  $online Online"
    }

    private fun hapticPulse() {
        if (!SecurePrefs.getVibrationEnabled()) return
        try {
            val vibrator = requireContext().getSystemService<Vibrator>()
            vibrator?.vibrate(VibrationEffect.createOneShot(25, VibrationEffect.DEFAULT_AMPLITUDE))
        } catch (_: Exception) {}
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
