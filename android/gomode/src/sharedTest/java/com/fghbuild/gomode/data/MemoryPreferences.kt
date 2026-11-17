// In-memory preferences DataStore shared by unit and instrumented tests.
package com.fghbuild.gomode.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Tests SettingsRepository logic, not androidx file persistence: in CI, the file-backed
 * PreferenceDataStoreFactory update loop has stopped emitting after a completed edit.
 *
 * Serializes each read-modify-write, as DataStore does. SettingsRepository edits from its own IO
 * scope while tests edit from theirs, so an unserialized fake loses writes.
 */
internal class MemoryPreferences : DataStore<Preferences> {
    /**
     * Runs under the write lock before each transform. Throw to fail the write; suspend to hold a
     * save in progress, which also queues later writes behind it.
     */
    @Volatile
    var beforeWrite: suspend () -> Unit = {}

    private val writes = Mutex()
    private val current = MutableStateFlow(emptyPreferences())
    override val data: Flow<Preferences> = current

    override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
        writes.withLock {
            beforeWrite()
            transform(current.value).also { current.value = it }
        }
}
