package com.acronet.ui

import android.app.AlertDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.acronet.crypto.AcroNetSecureDatabase
import com.ghost.app.R
import com.ghost.app.databinding.FragmentAcronetDashboardBinding

/**
 * AcroNetDashboardFragment — V8.5 Apex Synthesis
 *
 * Transport status indicator + Nostr pubkey handshake dialog.
 */
class AcroNetDashboardFragment : Fragment() {

    private var _binding: FragmentAcronetDashboardBinding? = null
    private val binding get() = _binding!!

    sealed class DashboardItem {
        data class SectionHeader(val title: String) : DashboardItem()
        data class Contact(val name: String, val lastMessage: String, val time: String,
                           val unread: Int, val isOnline: Boolean, val isDm: Boolean) : DashboardItem()
    }

    private val dashboardItems = mutableListOf<DashboardItem>()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentAcronetDashboardBinding.inflate(inflater, container, false)

        val insetPad = binding.dashHeader
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val sys = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            insetPad.setPadding(48, sys.top + 48, 48, 24)
            insets
        }

        populateDashboard()
        binding.rvDashboard.layoutManager = LinearLayoutManager(requireContext())
        binding.rvDashboard.adapter = DashboardAdapter()

        // FAB: Pubkey Handshake Dialog
        binding.fabNewChannel.setOnClickListener { showAddNodeDialog() }

        // Search / Transport Settings
        binding.btnSearch.setOnClickListener { AcroNetTransportSettings.show(requireContext()) }

        return binding.root
    }

    private fun populateDashboard() {
        dashboardItems.clear()
        dashboardItems.add(DashboardItem.SectionHeader("ACTIVE MESH ROOMS"))
        dashboardItems.add(DashboardItem.Contact("Team Scrapyard", "Shard #47 received", "2m", 3, true, false))
        dashboardItems.add(DashboardItem.Contact("Node 0x4A", "Key rotation complete", "18m", 0, true, false))
        dashboardItems.add(DashboardItem.Contact("Sector 7 Terminal", "Payload dispatched", "1h", 1, false, false))
        dashboardItems.add(DashboardItem.SectionHeader("DIRECT MESSAGES"))
        dashboardItems.add(DashboardItem.Contact("Sarthak", "Ready for Phase 3", "5m", 2, true, true))
        dashboardItems.add(DashboardItem.Contact("keyphox", "Encrypted payload...", "32m", 0, true, true))
        dashboardItems.add(DashboardItem.Contact("Ghost_0xBE", "Session expired", "3h", 0, false, true))
    }

    /**
     * AcroNetAddNodeDialog — Public Key Handshake UI
     * Input Nostr hex pubkey or scan QR to create a DM room.
     */
    private fun showAddNodeDialog() {
        val layout = android.widget.LinearLayout(requireContext()).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(48, 32, 48, 16)
        }
        val etPubkey = EditText(requireContext()).apply {
            hint = "Nostr Hex Pubkey (64 chars)"
            setTextColor(android.graphics.Color.WHITE)
            setHintTextColor(android.graphics.Color.parseColor("#555566"))
            setBackgroundColor(android.graphics.Color.parseColor("#1A1A2E"))
            setPadding(32, 24, 32, 24)
            textSize = 14f
            isSingleLine = true
        }
        val etAlias = EditText(requireContext()).apply {
            hint = "Display Name (e.g. keyphox)"
            setTextColor(android.graphics.Color.WHITE)
            setHintTextColor(android.graphics.Color.parseColor("#555566"))
            setBackgroundColor(android.graphics.Color.parseColor("#1A1A2E"))
            setPadding(32, 24, 32, 24)
            textSize = 14f
            isSingleLine = true
        }
        layout.addView(etPubkey)
        layout.addView(android.widget.Space(requireContext()).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(0, 16)
        })
        layout.addView(etAlias)

        AlertDialog.Builder(requireContext(), R.style.Theme_GhostChat)
            .setTitle("Add Mesh Node")
            .setMessage("Enter the peer's Nostr public key to initiate a Double Ratchet handshake.")
            .setView(layout)
            .setPositiveButton("🔗 Connect") { _, _ ->
                val pubkey = etPubkey.text.toString().trim()
                val alias = etAlias.text.toString().trim().ifBlank { pubkey.take(8) + "..." }
                if (pubkey.length == 64 && pubkey.matches(Regex("[0-9a-fA-F]+"))) {
                    val dmIdx = dashboardItems.indexOfLast { it is DashboardItem.Contact && (it as DashboardItem.Contact).isDm }
                    dashboardItems.add(dmIdx + 1,
                        DashboardItem.Contact(alias, "X25519 handshake initiated", "now", 0, true, true))
                    binding.rvDashboard.adapter?.notifyDataSetChanged()
                    Toast.makeText(requireContext(), "Double Ratchet handshake with $alias", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(requireContext(), "Invalid pubkey: must be 64 hex chars", Toast.LENGTH_SHORT).show()
                }
            }
            .setNeutralButton("📷 Scan QR") { _, _ ->
                Toast.makeText(requireContext(), "QR Scanner initializing...", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun navigateToChat(contact: DashboardItem.Contact) {
        val chatFragment = AcroNetChatFragment().apply {
            arguments = Bundle().apply {
                putString("ROOM_NAME", contact.name)
                putBoolean("IS_DM", contact.isDm)
            }
        }
        requireActivity().supportFragmentManager.beginTransaction()
            .setCustomAnimations(android.R.anim.fade_in, android.R.anim.fade_out, android.R.anim.fade_in, android.R.anim.fade_out)
            .replace((requireView().parent as ViewGroup).id, chatFragment)
            .addToBackStack(null).commit()
    }

    private inner class DashboardAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        private val TYPE_SECTION = 0; private val TYPE_CONTACT = 1

        override fun getItemViewType(pos: Int) = when (dashboardItems[pos]) {
            is DashboardItem.SectionHeader -> TYPE_SECTION; is DashboardItem.Contact -> TYPE_CONTACT
        }
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val layout = if (viewType == TYPE_SECTION) R.layout.item_dashboard_section else R.layout.item_dashboard_room
            return if (viewType == TYPE_SECTION) SectionVH(LayoutInflater.from(parent.context).inflate(layout, parent, false))
            else ContactVH(LayoutInflater.from(parent.context).inflate(layout, parent, false))
        }
        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, pos: Int) {
            when (val item = dashboardItems[pos]) {
                is DashboardItem.SectionHeader -> (holder as SectionVH).bind(item)
                is DashboardItem.Contact -> (holder as ContactVH).bind(item)
            }
        }
        override fun getItemCount() = dashboardItems.size

        inner class SectionVH(view: View) : RecyclerView.ViewHolder(view) {
            private val tv: TextView = view.findViewById(R.id.tvSectionTitle)
            fun bind(item: DashboardItem.SectionHeader) { tv.text = item.title }
        }
        inner class ContactVH(view: View) : RecyclerView.ViewHolder(view) {
            private val tvInit: TextView = view.findViewById(R.id.tvInitials)
            private val tvName: TextView = view.findViewById(R.id.tvRoomName)
            private val tvLast: TextView = view.findViewById(R.id.tvLastMessage)
            private val tvTime: TextView = view.findViewById(R.id.tvTimestamp)
            private val tvBadge: TextView = view.findViewById(R.id.tvUnreadBadge)
            private val vOnline: View = view.findViewById(R.id.viewOnlineStatus)

            init { view.setOnClickListener {
                val p = bindingAdapterPosition
                if (p != RecyclerView.NO_POSITION) (dashboardItems[p] as? DashboardItem.Contact)?.let { navigateToChat(it) }
            }}
            fun bind(item: DashboardItem.Contact) {
                tvName.text = item.name; tvLast.text = item.lastMessage; tvTime.text = item.time
                tvInit.text = item.name.take(1).uppercase()
                tvInit.setTextColor(if (item.isDm) android.graphics.Color.parseColor("#00F0FF") else android.graphics.Color.WHITE)
                vOnline.visibility = if (item.isOnline) View.VISIBLE else View.INVISIBLE
                if (item.unread > 0) { tvBadge.visibility = View.VISIBLE; tvBadge.text = item.unread.toString() }
                else tvBadge.visibility = View.GONE
            }
        }
    }

    override fun onDestroyView() { _binding = null; super.onDestroyView() }
}
