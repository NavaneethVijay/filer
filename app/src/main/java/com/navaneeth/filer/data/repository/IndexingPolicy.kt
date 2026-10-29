package com.navaneeth.filer.data.repository

import com.navaneeth.filer.data.inference.Embedding
import com.navaneeth.filer.data.inference.EngineTag

/** What a pass does next while it's waiting for the Mac (Developer mode). */
enum class MacWaitStep {
    /** The Mac answers again: carry on with it. */
    USE_MAC,
    /** "Index with Mac" was turned off: carry on with the phone's model. */
    USE_PHONE,
    /** Gone too long: end the pass; finished files stay done and the rest stay waiting. */
    GIVE_UP,
    KEEP_WAITING
}

/**
 * Pure rules for keeping the index consistent when the model can run in two places. Unit-tested.
 */
object IndexingPolicy {
    /** How long a pass waits for the Mac to come back before it ends. */
    const val MAC_WAIT_TIMEOUT_MS = 10 * 60 * 1000L

    /** How often the Mac is asked whether it's back while a pass waits. */
    const val MAC_RECHECK_MS = 5_000L

    /**
     * Mac failures for one file in a row, with the Mac otherwise answering, before the file is
     * left for a later pass (so one file the Mac can't handle never stalls a pass).
     */
    const val MAX_MAC_ATTEMPTS_PER_FILE = 3

    fun macWaitStep(wantsMac: Boolean, macReachable: Boolean, waitedMs: Long, timeoutMs: Long = MAC_WAIT_TIMEOUT_MS): MacWaitStep = when {
        !wantsMac -> MacWaitStep.USE_PHONE
        macReachable -> MacWaitStep.USE_MAC
        waitedMs >= timeoutMs -> MacWaitStep.GIVE_UP
        else -> MacWaitStep.KEEP_WAITING
    }

    /**
     * A file's vectors are saved only when every input got a real vector, all from one engine:
     * never a hash stand-in, never part Mac and part phone.
     */
    fun canSave(expected: Int, embeddings: List<Embedding>): Boolean =
        embeddings.size == expected &&
            embeddings.none { it.engine == EngineTag.FALLBACK || it.vector.isEmpty() } &&
            embeddings.map { it.engine }.distinct().size <= 1
}
