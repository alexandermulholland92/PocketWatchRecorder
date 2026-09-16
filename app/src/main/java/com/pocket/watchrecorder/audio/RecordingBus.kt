package com.pocket.watchrecorder.audio

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The seam between [RecordingService] and the UI.
 *
 * Capture no longer lives in the ViewModel, because a ViewModel dies with its
 * Activity and, from API 30 on, a process that is not foreground simply gets
 * silence from the microphone rather than an error. The service owns the
 * recorder; this object is how its state reaches whatever UI happens to exist.
 *
 * Service and UI run in the same process, so a plain flow is enough — there is
 * no IPC here and no binder to manage.
 */
object RecordingBus {

    /** Live capture state. [active] is the single source of truth for "are we recording". */
    data class Mic(
        val active: Boolean = false,
        val elapsedMs: Long = 0L,
        val level: Float = 0f
    )

    /** What a finished (or failed) capture produced. */
    sealed interface Outcome {
        /** The take is on disk and queued for upload under [entryId]. */
        data class Queued(val entryId: String) : Outcome

        /** Capture never started, or produced nothing usable. */
        data class Failed(val message: String) : Outcome
    }

    private val _mic = MutableStateFlow(Mic())
    val mic: StateFlow<Mic> = _mic.asStateFlow()

    /**
     * No replay: an outcome is a one-off event. Replaying it would re-focus a
     * stale recording every time a new ViewModel subscribed.
     */
    private val _outcomes = MutableSharedFlow<Outcome>(extraBufferCapacity = 8)
    val outcomes: SharedFlow<Outcome> = _outcomes.asSharedFlow()

    internal fun publishMic(elapsedMs: Long, level: Float) {
        _mic.value = Mic(active = true, elapsedMs = elapsedMs, level = level)
    }

    internal fun clearMic() {
        _mic.value = Mic()
    }

    internal fun publish(outcome: Outcome) {
        _outcomes.tryEmit(outcome)
    }
}
