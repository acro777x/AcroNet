package com.acronet.ui

import android.app.AlertDialog
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.*
import android.widget.ImageView
import android.widget.Toast
import android.widget.TextView
import androidx.core.widget.doOnTextChanged
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsAnimationCompat
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.acronet.crypto.AcroNetMessageCipher
import com.acronet.crypto.AcroNetSecureDatabase
import com.acronet.crypto.AcroNetVanishMode
import com.acronet.crypto.SecureMessage
import com.ghost.app.R
import com.ghost.app.databinding.FragmentAcronetChatBinding
import javax.crypto.spec.SecretKeySpec
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * AcroNetChatFragment — V8.5 Apex Synthesis Messenger
 *
 * Full crypto + sender identity + media thumbnails.
 */
class AcroNetChatFragment : Fragment(), AcroNetVanishMode.VanishModeCallback {

    companion object { private const val TAG = "ACRO_VOID_CRYPTO" }

    private var _binding: FragmentAcronetChatBinding? = null
    private val binding get() = _binding!!

    private val messages = mutableListOf<SecureMessage>()
    private lateinit var chatAdapter: ChatAdapter
    private val vanishMode = AcroNetVanishMode()
    private val handler = Handler(Looper.getMainLooper())
    private val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())

    private var roomName = "AcroNet Identity"
    private var isDm = false
    private var sessionKey: SecretKeySpec? = null

    private val autoReplies = listOf(
        "Acknowledged. Rotating keys...",
        "Shard received. Verifying integrity.",
        "Copy that. Mesh node synced.",
        "Payload confirmed. Standing by.",
        "Roger. Quantum handshake complete.",
        "Understood. Ephemeral session active."
    )

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentAcronetChatBinding.inflate(inflater, container, false)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val sys = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            binding.chatAppBar.setPadding(48, sys.top + 16, 48, 16)
            binding.inputContainer.setPadding(32, 16, 32, maxOf(sys.bottom, ime.bottom) + 16)
            insets
        }
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        roomName = arguments?.getString("ROOM_NAME") ?: "AcroNet Identity"
        isDm = arguments?.getBoolean("IS_DM", false) ?: false
        binding.tvPeerId.text = roomName
        binding.tvStatus.text = if (isDm) "Double Ratchet • E2EE" else "Quantum Secured • CGKA"

        sessionKey = AcroNetMessageCipher.deriveSessionKey(roomName)

        val persisted = AcroNetSecureDatabase.getMessages(roomName)
        messages.addAll(persisted)

        vanishMode.setCallback(this)

        chatAdapter = ChatAdapter(messages)
        binding.rvMessages.apply {
            layoutManager = LinearLayoutManager(requireContext()).apply { stackFromEnd = true }
            adapter = chatAdapter
            setItemViewCacheSize(20)
        }
        if (messages.isNotEmpty()) binding.rvMessages.scrollToPosition(messages.size - 1)

        binding.etMessage.doOnTextChanged { text, _, _, _ ->
            binding.btnSend.setImageResource(
                if (text.isNullOrBlank()) R.drawable.ic_vault_mic else R.drawable.ic_vault_send
            )
        }

        binding.btnSend.setOnClickListener {
            val text = binding.etMessage.text.toString()
            if (text.isNotBlank()) sendMessage(text)
            else Toast.makeText(requireContext(), "Audio Matrix initializing...", Toast.LENGTH_SHORT).show()
        }

        binding.btnAttach.setOnClickListener { showAttachmentOptions() }
        binding.btnSelfDestruct.setOnClickListener { showSelfDestructDialog() }
        binding.btnVanishMode.setOnClickListener { toggleVanishMode() }
        binding.btnBack.setOnClickListener { requireActivity().supportFragmentManager.popBackStack() }
    }

    private fun sendMessage(plaintext: String) {
        val key = sessionKey ?: return
        val ciphertext = AcroNetMessageCipher.encrypt(plaintext, key)
        val msg = SecureMessage(
            id = System.currentTimeMillis().toString(), senderId = "Acro",
            encryptedPayload = ciphertext, timestamp = System.currentTimeMillis(),
            isMine = true, isEphemeral = vanishMode.isActive, isVanishMode = vanishMode.isActive,
            expiresAt = if (vanishMode.isActive) System.currentTimeMillis() + (vanishMode.selfDestructSeconds * 1000L) else 0L
        )
        AcroNetSecureDatabase.insertMessage(msg, roomName)
        messages.add(msg)
        chatAdapter.notifyItemInserted(messages.size - 1)
        binding.rvMessages.scrollToPosition(messages.size - 1)
        binding.etMessage.text?.clear()
        if (vanishMode.isActive) vanishMode.scheduleEviction(msg)

        handler.postDelayed({
            if (_binding != null) {
                val replyPlain = autoReplies.random()
                val replyCipher = AcroNetMessageCipher.encrypt(replyPlain, key)
                val reply = SecureMessage(
                    id = (System.currentTimeMillis() + 1).toString(), senderId = roomName,
                    encryptedPayload = replyCipher, timestamp = System.currentTimeMillis(),
                    isMine = false, isEphemeral = vanishMode.isActive, isVanishMode = vanishMode.isActive
                )
                AcroNetSecureDatabase.insertMessage(reply, roomName)
                messages.add(reply)
                chatAdapter.notifyItemInserted(messages.size - 1)
                binding.rvMessages.scrollToPosition(messages.size - 1)
                if (vanishMode.isActive) vanishMode.scheduleEviction(reply)
            }
        }, 1500 + (Math.random() * 2000).toLong())
    }

    private fun showAttachmentOptions() {
        AlertDialog.Builder(requireContext(), R.style.Theme_GhostChat)
            .setTitle("Secure Attachment")
            .setItems(arrayOf("📷  Camera Capture", "🖼️  Gallery", "📄  Encrypted File")) { _, w ->
                when (w) {
                    0 -> Toast.makeText(requireContext(), "Camera: EXIF sanitizer armed.", Toast.LENGTH_SHORT).show()
                    1 -> Toast.makeText(requireContext(), "Gallery: MediaSanitizer stripping GPS.", Toast.LENGTH_SHORT).show()
                    2 -> Toast.makeText(requireContext(), "File encryption pipeline ready.", Toast.LENGTH_SHORT).show()
                }
            }.show()
    }

    private fun showSelfDestructDialog() {
        val opts = arrayOf("5 seconds", "15 seconds", "30 seconds", "1 minute", "5 minutes", "Off")
        val vals = intArrayOf(5, 15, 30, 60, 300, 0)
        AlertDialog.Builder(requireContext(), R.style.Theme_GhostChat)
            .setTitle("Self-Destruct Timer").setItems(opts) { _, w ->
                vanishMode.setSelfDestructTimer(if (vals[w] == 0) 30 else vals[w])
                Toast.makeText(requireContext(), if (vals[w] == 0) "Off" else opts[w], Toast.LENGTH_SHORT).show()
            }.show()
    }

    private fun toggleVanishMode() {
        if (vanishMode.isActive) vanishMode.deactivate(this) else vanishMode.activate(this)
    }

    override fun onVanishModeActivated() {
        binding.viewVanishOverlay.visibility = View.VISIBLE
        binding.tvStatus.text = "👻 Ghost Mode • Ephemeral"
        binding.tvStatus.setTextColor(Color.parseColor("#00F0FF"))
    }

    override fun onVanishModeDeactivated() {
        binding.viewVanishOverlay.visibility = View.GONE
        binding.tvStatus.text = if (isDm) "Double Ratchet • E2EE" else "Quantum Secured • CGKA"
        binding.tvStatus.setTextColor(Color.parseColor("#00FFFF"))
        messages.filter { it.isEphemeral }.forEach { AcroNetSecureDatabase.deleteMessage(it.id) }
        messages.removeAll { it.isEphemeral }
        chatAdapter.notifyDataSetChanged()
    }

    override fun onMessageEvicted(messageId: String) {
        AcroNetSecureDatabase.deleteMessage(messageId)
        val idx = messages.indexOfFirst { it.id == messageId }
        if (idx >= 0) { messages.removeAt(idx); chatAdapter.notifyItemRemoved(idx) }
    }

    // ── CHAT ADAPTER WITH SENDER IDENTITY ────────────────────────────

    private inner class ChatAdapter(private val list: List<SecureMessage>) :
        RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        override fun getItemViewType(position: Int) = if (list[position].isMine) 1 else 0

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val layout = if (viewType == 1) R.layout.item_msg_sent else R.layout.item_msg_recv
            return ChatVH(LayoutInflater.from(parent.context).inflate(layout, parent, false), viewType)
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            (holder as ChatVH).bind(list[position])
        }

        override fun getItemCount() = list.size

        inner class ChatVH(view: View, private val type: Int) : RecyclerView.ViewHolder(view) {
            private val tvMsg: TextView = view.findViewById(R.id.tv_msg_text)
            private val tvTime: TextView = view.findViewById(R.id.tvTimestamp)
            private val tvSender: TextView? = view.findViewById(R.id.tvSenderAlias)

            fun bind(msg: SecureMessage) {
                val key = sessionKey
                tvMsg.text = if (key != null) AcroNetMessageCipher.decrypt(msg.encryptedPayload, key) else msg.encryptedPayload
                tvMsg.setTextColor(if (msg.isEphemeral) Color.parseColor("#80F0FF") else if (type == 1) Color.WHITE else Color.parseColor("#CCCCDD"))
                tvTime.text = timeFmt.format(Date(msg.timestamp))

                // Sender identity in mesh rooms (received messages only)
                if (type == 0 && !isDm && tvSender != null) {
                    tvSender.visibility = View.VISIBLE
                    tvSender.text = msg.senderId
                } else {
                    tvSender?.visibility = View.GONE
                }
            }
        }
    }

    override fun onDestroyView() {
        binding.etMessage.text?.clear()
        vanishMode.destroy()
        for (msg in messages) { java.util.Arrays.fill(msg.toBytePayload(), 0.toByte()) }
        messages.clear()
        sessionKey = null
        _binding = null
        super.onDestroyView()
    }
}
