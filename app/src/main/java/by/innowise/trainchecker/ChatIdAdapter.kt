package by.innowise.trainchecker

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import by.innowise.trainchecker.databinding.ItemChatIdBinding

class ChatIdAdapter(
    private val chatIds: MutableList<String>,
    private val onDeleteClick: (String) -> Unit
) : RecyclerView.Adapter<ChatIdAdapter.ChatIdViewHolder>() {

    inner class ChatIdViewHolder(val binding: ItemChatIdBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChatIdViewHolder {
        val binding = ItemChatIdBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ChatIdViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ChatIdViewHolder, position: Int) {
        val chatId = chatIds[position]
        holder.binding.chatIdText.text = chatId
        
        holder.binding.root.setOnClickListener {
            onDeleteClick(chatId)
        }
    }

    override fun getItemCount() = chatIds.size

    fun removeItem(chatId: String) {
        val position = chatIds.indexOf(chatId)
        if (position != -1) {
            chatIds.removeAt(position)
            notifyItemRemoved(position)
        }
    }
}
