package by.innowise.trainchecker

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "passenger_profiles")
data class PassengerProfile(
    @PrimaryKey
    val name: String,
    val lastName: String,
    val firstName: String,
    val middleName: String,
    val documentNumber: String,
    val rwLogin: String,
    val hasSavedRwPassword: Boolean = false,
    val chatId: String,
    val updatedAt: Long = System.currentTimeMillis()
)
