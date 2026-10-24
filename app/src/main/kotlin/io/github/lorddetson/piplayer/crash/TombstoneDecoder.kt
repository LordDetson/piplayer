package io.github.lorddetson.piplayer.crash

/**
 * Turns a native crash's tombstone (Android 12 and later: AOSP's tombstone.proto) into text laid out as Android's own
 * crash dumps (docs/playback.md §7.1), with no protobuf library: a small reader of the fields it needs. Fields it does
 * not know, from newer Android versions or vendors, are skipped.
 */
object TombstoneDecoder {

    class MalformedTombstone(message: String) : Exception(message)

    fun decode(bytes: ByteArray): String {
        val tombstone = Tombstone()
        fields(bytes, 0, bytes.size) { tombstone.read(it) }
        return tombstone.text()
    }

    /** One field as read: a number for varints, else the range of its bytes. */
    private class Field(val number: Int, val varint: Long, private val bytes: ByteArray, private val start: Int, private val end: Int) {
        val int: Int get() = varint.toInt()
        val string: String get() = String(bytes, start, end - start, Charsets.UTF_8)
        fun forEach(block: (Field) -> Unit) = fields(bytes, start, end, block)
    }

    private fun fields(bytes: ByteArray, from: Int, to: Int, block: (Field) -> Unit) {
        var position = from
        fun varint(): Long {
            var value = 0L
            var shift = 0
            while (true) {
                if (position >= to) throw MalformedTombstone("A number runs past the end at $position")
                val byte = bytes[position++].toInt()
                value = value or ((byte and 0x7F).toLong() shl shift)
                if (byte and 0x80 == 0) return value
                shift += 7
                if (shift >= 64) throw MalformedTombstone("A number is longer than ten bytes at $position")
            }
        }
        fun skip(count: Long) {
            if (count < 0 || count > to - position) throw MalformedTombstone("A field runs past the end at $position")
            position += count.toInt()
        }
        while (position < to) {
            val key = varint()
            val number = (key ushr 3).toInt()
            if (number <= 0) throw MalformedTombstone("Field number $number at $position")
            when ((key and 7).toInt()) {
                0 -> block(Field(number, varint(), bytes, 0, 0))
                1 -> skip(8)
                2 -> {
                    val length = varint()
                    val start = position
                    skip(length)
                    block(Field(number, 0, bytes, start, position))
                }
                5 -> skip(4)
                else -> throw MalformedTombstone("Wire type ${key and 7} at $position")
            }
        }
    }

    private class Frame {
        var relPc = 0L
        var functionName = ""
        var functionOffset = 0L
        var fileName = ""
        var fileMapOffset = 0L
        var buildId = ""

        fun read(field: Field) {
            when (field.number) {
                1 -> relPc = field.varint
                4 -> functionName = field.string
                5 -> functionOffset = field.varint
                6 -> fileName = field.string
                7 -> fileMapOffset = field.varint
                8 -> buildId = field.string
            }
        }
    }

    private class ThreadDump(var id: Int) {
        var name = ""
        val frames = mutableListOf<Frame>()
        val notes = mutableListOf<String>()

        fun read(field: Field) {
            when (field.number) {
                1 -> id = field.int
                2 -> name = field.string
                4 -> frames += Frame().also { frame -> field.forEach(frame::read) }
                7 -> notes += field.string
            }
        }
    }

    private class Tombstone {
        var arch = -1
        var fingerprint = ""
        var revision = ""
        var timestamp = ""
        var pid = 0
        var tid = 0
        var uid = 0
        val commandLine = mutableListOf<String>()
        var signal: String? = null
        var abortMessage = ""
        val causes = mutableListOf<String>()
        val threads = mutableListOf<ThreadDump>()
        var uptime = 0L

        fun read(field: Field) {
            when (field.number) {
                1 -> arch = field.int
                2 -> fingerprint = field.string
                3 -> revision = field.string
                4 -> timestamp = field.string
                5 -> pid = field.int
                6 -> tid = field.int
                7 -> uid = field.int
                9 -> commandLine += field.string
                10 -> signal = signal(field)
                14 -> abortMessage = field.string
                15 -> field.forEach { if (it.number == 1) causes += it.string }
                16 -> threads += thread(field)
                20 -> uptime = field.varint
            }
        }

        private fun signal(field: Field): String {
            var number = 0
            var name = ""
            var code = 0
            var codeName = ""
            var hasFaultAddress = false
            var faultAddress = 0L
            field.forEach {
                when (it.number) {
                    1 -> number = it.int
                    2 -> name = it.string
                    3 -> code = it.int
                    4 -> codeName = it.string
                    8 -> hasFaultAddress = it.varint != 0L
                    9 -> faultAddress = it.varint
                }
            }
            val fault = if (hasFaultAddress) "0x" + hex(faultAddress) else "--------"
            return "signal $number ($name), code $code ($codeName), fault addr $fault"
        }

        /** A thread is an entry of a map from its id: the key, then the thread. */
        private fun thread(field: Field): ThreadDump {
            val thread = ThreadDump(0)
            field.forEach { entry ->
                when (entry.number) {
                    1 -> if (thread.id == 0) thread.id = entry.int
                    2 -> entry.forEach(thread::read)
                }
            }
            return thread
        }

        fun text(): String = buildString {
            val command = commandLine.joinToString(" ")
            val crashing = threads.firstOrNull { it.id == tid }
            appendLine("*** *** *** *** *** *** *** *** *** *** *** *** *** *** *** ***")
            appendLine("Build fingerprint: '$fingerprint'")
            if (revision.isNotEmpty()) appendLine("Revision: '$revision'")
            appendLine("ABI: '${ARCHES.getOrElse(arch) { "unknown $arch" }}'")
            appendLine("Timestamp: $timestamp")
            if (uptime > 0) appendLine("Process uptime: ${uptime}s")
            appendLine("Cmdline: $command")
            appendLine("pid: $pid, tid: $tid, name: ${crashing?.name.orEmpty()}  >>> $command <<<")
            appendLine("uid: $uid")
            signal?.let(::appendLine)
            causes.forEach { appendLine("Cause: $it") }
            if (abortMessage.isNotEmpty()) appendLine("Abort message: '$abortMessage'")
            crashing?.let { backtrace(it) }
            threads.filter { it !== crashing }.sortedBy { it.id }.forEach { thread ->
                appendLine()
                appendLine("--- --- --- --- --- --- --- --- --- --- --- --- --- --- --- ---")
                appendLine("pid: $pid, tid: ${thread.id}, name: ${thread.name}  >>> $command <<<")
                backtrace(thread)
            }
        }

        private fun StringBuilder.backtrace(thread: ThreadDump) {
            appendLine()
            thread.notes.forEach { appendLine("  NOTE: $it") }
            appendLine("backtrace:")
            thread.frames.forEachIndexed { index, frame ->
                append("      #%02d pc %s  %s".format(index, pc(frame.relPc), frame.fileName.ifEmpty { "<unknown>" }))
                if (frame.fileMapOffset != 0L) append(" (offset 0x${frame.fileMapOffset.toString(16)})")
                if (frame.functionName.isNotEmpty()) {
                    append(" (${frame.functionName}")
                    if (frame.functionOffset != 0L) append("+${frame.functionOffset}")
                    append(")")
                }
                if (frame.buildId.isNotEmpty()) append(" (BuildId: ${frame.buildId})")
                appendLine()
            }
        }

        private fun pc(value: Long): String = if (arch in WIDE) hex(value) else hex(value).takeLast(8)

        private fun hex(value: Long): String = "%016x".format(value)
    }

    private val ARCHES = listOf("arm", "arm64", "x86", "x86_64", "riscv64", "none")
    private val WIDE = setOf(1, 3, 4)
}
