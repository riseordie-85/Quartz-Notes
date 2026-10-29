package com.quartz.notes.data.sync

import kotlinx.coroutines.flow.Flow

/**
 * The appearance settings every device and the web app share through the server: accent and
 * background colours. [changedAt] is when they were last changed here (0 = never set).
 */
data class Appearance(val accentArgb: Int, val backgroundArgb: Int, val changedAt: Long)

/** Where this device keeps its appearance settings. */
interface AppearanceStore {
    suspend fun local(): Appearance

    /** Takes a newer version from another device. */
    suspend fun applyRemote(appearance: Appearance)

    /** Emits whenever the settings change here. */
    fun changes(): Flow<Any>
}

/** In-memory version, for tests. */
class MemoryAppearanceStore(var value: Appearance = Appearance(0, 0, 0)) : AppearanceStore {
    private val flow = kotlinx.coroutines.flow.MutableStateFlow(value)

    override suspend fun local() = value

    override suspend fun applyRemote(appearance: Appearance) {
        value = appearance
        flow.value = appearance
    }

    fun set(accentArgb: Int, backgroundArgb: Int, at: Long) {
        value = Appearance(accentArgb, backgroundArgb, at)
        flow.value = value
    }

    override fun changes(): Flow<Any> = flow
}
