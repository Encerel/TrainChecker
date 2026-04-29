package by.innowise.trainchecker

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface PassengerProfileDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(profile: PassengerProfile)

    @Query("SELECT * FROM passenger_profiles ORDER BY name COLLATE NOCASE")
    suspend fun getAll(): List<PassengerProfile>

    @Query("SELECT * FROM passenger_profiles WHERE name = :name LIMIT 1")
    suspend fun getByName(name: String): PassengerProfile?

    @Query("DELETE FROM passenger_profiles WHERE name = :name")
    suspend fun delete(name: String)
}
