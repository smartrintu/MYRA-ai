package com.myra.assistant.data

import android.os.Handler
import android.os.Looper
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Live "terminal" of everything MYRA does in the background (phone commands, website code as it is written).
 * Any thread may write; the UI listener is called on the main thread (throttled).
 */
object TerminalLog {

    interface Listener {
        fun onTerminal(text: String, busy: Boolean)
    }

    private const val MAX_CHARS = 6000

    private val main = Handler(Looper.getMainLooper())
    private val buf = StringBuilder()
    private val jobs = AtomicInteger(0)
    private val pending = AtomicBoolean(false)

    @Volatile var listener: Listener? = null

    val busy: Boolean get() = jobs.get() > 0

    private val push = Runnable {
        pending.set(false)
        listener?.onTerminal(text(), busy)
    }

    @Synchronized fun text(): String = buf.toString()

    /** Starts a job: prints "$ command" and marks the terminal as running. */
    fun begin(command: String) {
        jobs.incrementAndGet()
        append("\n$ $command\n")
    }

    fun info(line: String) = append(line + "\n")

    fun ok(line: String) = append("✔ $line\n")

    fun err(line: String) = append("✘ $line\n")

    fun end() {
        if (jobs.decrementAndGet() < 0) jobs.set(0)
        schedule()
    }

    @Synchronized fun append(s: String) {
        buf.append(s)
        if (buf.length > MAX_CHARS) buf.delete(0, buf.length - MAX_CHARS)
        schedule()
    }

    private fun schedule() {
        if (pending.compareAndSet(false, true)) main.postDelayed(push, 60)
    }
}
