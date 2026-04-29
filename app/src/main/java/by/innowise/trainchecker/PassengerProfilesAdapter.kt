package by.innowise.trainchecker

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import by.innowise.trainchecker.databinding.ItemPassengerProfileBinding

class PassengerProfilesAdapter(
    private val profiles: List<PassengerProfile>,
    private val onEditClick: (PassengerProfile) -> Unit,
    private val onDeleteClick: (PassengerProfile) -> Unit
) : RecyclerView.Adapter<PassengerProfilesAdapter.ProfileViewHolder>() {

    inner class ProfileViewHolder(val binding: ItemPassengerProfileBinding) :
        RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ProfileViewHolder {
        val binding = ItemPassengerProfileBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ProfileViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ProfileViewHolder, position: Int) {
        val profile = profiles[position]
        holder.binding.profileName.text = profile.name
        holder.binding.profileDetails.text = buildString {
            append("${profile.lastName} ${profile.firstName}")
            if (profile.middleName.isNotBlank()) append(" ${profile.middleName}")
            append("\nДокумент: ${profile.documentNumber}")
            append("\nChat ID: ${profile.chatId.ifBlank { "не указан" }}")
            append("\nЛогин: ${profile.rwLogin.ifBlank { "не указан" }}")
            append("\nПароль: ${if (profile.hasSavedRwPassword) "сохранен" else "не указан"}")
        }
        holder.binding.buttonEditProfile.setOnClickListener { onEditClick(profile) }
        holder.binding.buttonDeleteProfile.setOnClickListener { onDeleteClick(profile) }
    }

    override fun getItemCount(): Int = profiles.size
}
