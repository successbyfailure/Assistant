package com.sbf.assistant

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import io.noties.markwon.Markwon

class ChatAdapter(
    private val messages: List<ChatMessage>,
    context: Context,
    private val onCancelClick: (Int) -> Unit,
    private val onStatsClick: (Int) -> Unit,
    private val onThoughtToggle: (Int) -> Unit
) : RecyclerView.Adapter<ChatAdapter.ViewHolder>() {

    private val markwon = Markwon.create(context)

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvMessage: TextView = view.findViewById(R.id.tv_message)
        val tvTools: TextView? = view.findViewById(R.id.tv_tools)
        val btnCancel: MaterialButton? = view.findViewById(R.id.btn_cancel)
        val ivStats: ImageView? = view.findViewById(R.id.iv_stats)
        val cardThought: View? = view.findViewById(R.id.card_thought)
        val tvThought: TextView? = view.findViewById(R.id.tv_thought)
        val ivThoughtToggle: ImageView? = view.findViewById(R.id.iv_thought_toggle)
    }

    override fun getItemViewType(position: Int): Int {
        return if (messages[position].isUser) 0 else 1
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val layout = if (viewType == 0) R.layout.item_chat_user else R.layout.item_chat_assistant
        val view = LayoutInflater.from(parent.context).inflate(layout, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val message = messages[position]
        if (message.isUser) {
            holder.tvMessage.text = message.text
            holder.tvTools?.visibility = View.GONE
            holder.btnCancel?.visibility = View.GONE
            holder.ivStats?.visibility = View.GONE
        } else {
            markwon.setMarkdown(holder.tvMessage, message.text)
            holder.cardThought?.let { card ->
                if (message.thought.isNotBlank()) {
                    card.visibility = View.VISIBLE
                    holder.tvThought?.text = if (message.thoughtCollapsed && !message.isThinking) {
                        message.thought.lines().firstOrNull().orEmpty()
                    } else {
                        message.thought
                    }
                    holder.ivThoughtToggle?.apply {
                        visibility = if (message.isThinking) View.GONE else View.VISIBLE
                        rotation = if (message.thoughtCollapsed) 0f else 180f
                        setOnClickListener {
                            val pos = holder.adapterPosition
                            if (pos != RecyclerView.NO_POSITION) {
                                onThoughtToggle(pos)
                            }
                        }
                    }
                    if (message.isThinking) {
                        card.alpha = 0.9f
                    } else {
                        card.alpha = 1f
                    }
                } else {
                    card.visibility = View.GONE
                }
            }
            val toolsText = if (message.toolNames.isNotEmpty()) {
                "Tools: ${message.toolNames.joinToString(", ")}"
            } else {
                ""
            }
            holder.tvTools?.apply {
                text = toolsText
                visibility = if (toolsText.isNotBlank()) View.VISIBLE else View.GONE
            }
            holder.btnCancel?.apply {
                visibility = if (message.showCancel) View.VISIBLE else View.GONE
                setOnClickListener {
                    val pos = holder.adapterPosition
                    if (pos != RecyclerView.NO_POSITION) {
                        onCancelClick(pos)
                    }
                }
            }
            holder.ivStats?.apply {
                visibility = if (message.stats != null) View.VISIBLE else View.GONE
                setOnClickListener {
                    val pos = holder.adapterPosition
                    if (pos != RecyclerView.NO_POSITION) {
                        onStatsClick(pos)
                    }
                }
            }
        }
    }

    override fun getItemCount() = messages.size
}
