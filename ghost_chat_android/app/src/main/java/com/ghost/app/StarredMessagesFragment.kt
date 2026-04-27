package com.ghost.app

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/**
 * StarredMessagesFragment — Shows all starred (bookmarked) messages.
 * Phase 4: 100% local.
 */
class StarredMessagesFragment : Fragment() {

    private var allMessages: List<ChatMessage> = emptyList()

    companion object {
        fun newInstance(messages: List<ChatMessage>): StarredMessagesFragment {
            return StarredMessagesFragment().apply {
                allMessages = messages
            }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_starred, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val btnBack = view.findViewById<ImageButton>(R.id.btn_starred_back)
        btnBack.setOnClickListener { (activity as? MainActivity)?.onBackPressed() }

        val rv = view.findViewById<RecyclerView>(R.id.rv_starred)
        rv.layoutManager = LinearLayoutManager(requireContext())

        val starred = allMessages.filter { SecurePrefs.isStarred(it.id) }
        val adapter = MessageAdapter()
        rv.adapter = adapter
        adapter.submitList(starred)

        val tvEmpty = view.findViewById<TextView>(R.id.tv_empty_starred)
        tvEmpty.visibility = if (starred.isEmpty()) View.VISIBLE else View.GONE
    }
}
