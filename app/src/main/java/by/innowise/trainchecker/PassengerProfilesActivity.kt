package by.innowise.trainchecker

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import by.innowise.trainchecker.databinding.ActivityPassengerProfilesBinding
import kotlinx.coroutines.launch

class PassengerProfilesActivity : AppCompatActivity() {
    private lateinit var binding: ActivityPassengerProfilesBinding
    private lateinit var passengerProfileRepository: PassengerProfileRepository
    private var editingProfileName: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPassengerProfilesBinding.inflate(layoutInflater)
        setContentView(binding.root)

        passengerProfileRepository = PassengerProfileRepository(this)

        binding.toolbar.setNavigationOnClickListener {
            finish()
        }
        binding.passengerProfilesRecyclerView.layoutManager = LinearLayoutManager(this)
        binding.buttonSaveProfile.setOnClickListener {
            saveProfile()
        }
        binding.buttonClearProfileForm.setOnClickListener {
            clearForm()
        }

        loadProfiles()
    }

    private fun loadProfiles() {
        lifecycleScope.launch {
            val profiles = passengerProfileRepository.getAll()
            binding.passengerProfilesRecyclerView.adapter = PassengerProfilesAdapter(
                profiles = profiles,
                onEditClick = { profile -> fillForm(profile) },
                onDeleteClick = { profile -> confirmDelete(profile) }
            )
        }
    }

    private fun fillForm(profile: PassengerProfile) {
        editingProfileName = profile.name
        binding.formTitle.text = "Редактирование профиля"
        binding.editProfileName.setText(profile.name)
        binding.editProfileChatId.setText(profile.chatId)
        binding.editProfileRwLogin.setText(profile.rwLogin)
        binding.editProfileRwPassword.text?.clear()
        binding.profilePasswordInputLayout.helperText = if (profile.hasSavedRwPassword) {
            "Пароль сохранен. Оставьте пустым, чтобы не менять"
        } else {
            null
        }
        binding.editProfileLastName.setText(profile.lastName)
        binding.editProfileFirstName.setText(profile.firstName)
        binding.editProfileMiddleName.setText(profile.middleName)
        binding.editProfileDocument.setText(profile.documentNumber)
        binding.editProfileName.requestFocus()
    }

    private fun saveProfile() {
        val profileName = binding.editProfileName.text.toString().trim()
        val chatId = binding.editProfileChatId.text.toString().trim()
        val rwLogin = binding.editProfileRwLogin.text.toString().trim()
        val rwPassword = binding.editProfileRwPassword.text.toString()
        val lastName = binding.editProfileLastName.text.toString().trim()
        val firstName = binding.editProfileFirstName.text.toString().trim()
        val middleName = binding.editProfileMiddleName.text.toString().trim()
        val documentNumber = binding.editProfileDocument.text.toString().trim()

        if (profileName.isBlank()) {
            Toast.makeText(this, "Название профиля не указано", Toast.LENGTH_SHORT).show()
            return
        }
        if (chatId.isBlank()) {
            Toast.makeText(this, "Chat ID не указан", Toast.LENGTH_SHORT).show()
            return
        }
        if (rwLogin.isBlank()) {
            Toast.makeText(this, "Логин pass.rw.by не указан", Toast.LENGTH_SHORT).show()
            return
        }
        if (lastName.isBlank() || firstName.isBlank() || documentNumber.isBlank()) {
            Toast.makeText(
                this,
                "Заполните фамилию, имя и номер документа",
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        lifecycleScope.launch {
            val previousName = editingProfileName
            val hasExistingPassword = previousName
                ?.let { PassengerProfilePasswordManager.hasPassword(this@PassengerProfilesActivity, it) }
                ?: false

            if (rwPassword.isBlank() && !hasExistingPassword) {
                Toast.makeText(
                    this@PassengerProfilesActivity,
                    "Пароль pass.rw.by не указан",
                    Toast.LENGTH_SHORT
                ).show()
                return@launch
            }

            if (previousName != null && previousName != profileName) {
                if (rwPassword.isBlank()) {
                    PassengerProfilePasswordManager.movePassword(
                        this@PassengerProfilesActivity,
                        previousName,
                        profileName
                    )
                }
                passengerProfileRepository.delete(previousName)
            }

            if (rwPassword.isNotBlank()) {
                PassengerProfilePasswordManager.savePassword(
                    this@PassengerProfilesActivity,
                    profileName,
                    rwPassword
                )
            }

            passengerProfileRepository.save(
                PassengerProfile(
                    name = profileName,
                    lastName = lastName,
                    firstName = firstName,
                    middleName = middleName,
                    documentNumber = documentNumber,
                    rwLogin = rwLogin,
                    hasSavedRwPassword = PassengerProfilePasswordManager.hasPassword(
                        this@PassengerProfilesActivity,
                        profileName
                    ),
                    chatId = chatId
                )
            )

            clearForm()
            loadProfiles()
            Toast.makeText(
                this@PassengerProfilesActivity,
                "Профиль сохранен",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun confirmDelete(profile: PassengerProfile) {
        AlertDialog.Builder(this)
            .setTitle("Удалить профиль?")
            .setMessage("Профиль \"${profile.name}\" будет удален.")
            .setPositiveButton("Удалить") { _, _ ->
                lifecycleScope.launch {
                    passengerProfileRepository.delete(profile.name)
                    PassengerProfilePasswordManager.deletePassword(
                        this@PassengerProfilesActivity,
                        profile.name
                    )
                    if (editingProfileName == profile.name) {
                        clearForm()
                    }
                    loadProfiles()
                    Toast.makeText(
                        this@PassengerProfilesActivity,
                        "Профиль удален",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun clearForm() {
        editingProfileName = null
        binding.formTitle.text = "Новый профиль"
        binding.editProfileName.text?.clear()
        binding.editProfileChatId.text?.clear()
        binding.editProfileRwLogin.text?.clear()
        binding.editProfileRwPassword.text?.clear()
        binding.profilePasswordInputLayout.helperText = null
        binding.editProfileLastName.text?.clear()
        binding.editProfileFirstName.text?.clear()
        binding.editProfileMiddleName.text?.clear()
        binding.editProfileDocument.text?.clear()
    }
}
