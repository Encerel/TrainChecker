package by.innowise.trainchecker

import android.content.Context

class PassengerProfileRepository(context: Context) {
    private val dao = TrainCheckerDatabase.getInstance(context).passengerProfileDao()

    suspend fun save(profile: PassengerProfile) {
        dao.save(profile)
    }

    suspend fun getAll(): List<PassengerProfile> {
        return dao.getAll()
    }

    suspend fun getByName(name: String): PassengerProfile? {
        return dao.getByName(name)
    }

    suspend fun delete(name: String) {
        dao.delete(name)
    }
}
