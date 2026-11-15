package io.github.lorddetson.piplayer.spike

import io.github.lorddetson.piplayer.net.OnlineGate
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * The debug spike's online gate (debug builds only): open only while a spike run goes on, which the spike screen
 * starts and cancels when it closes, or while an explicit opt-in run (the own-files check) holds it. The app's own
 * services keep their closed gate.
 */
object SpikeGate : OnlineGate {

    private val holders = AtomicInteger()

    override fun isOpen(): Boolean = holders.get() > 0

    /** Opens the gate until the returned handle is closed; handles nest, and closing one twice counts once. */
    fun open(): Closeable {
        holders.incrementAndGet()
        val closed = AtomicBoolean(false)
        return Closeable { if (closed.compareAndSet(false, true)) holders.decrementAndGet() }
    }
}
