package com.eslee.llmusage.core.web

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * A short, shareable record of what the sign-in browser and the background
 * reader did, so a failure on a phone this project cannot attach to a debugger
 * can still be diagnosed from evidence instead of guesswork.
 *
 * Only navigation shape, page classification and the figures read are kept. URLs
 * are reduced to scheme, host and path, which drops OAuth codes and tokens carried
 * in queries, and free text is scrubbed of addresses and long secrets before it
 * is stored.
 */
object WebTrace {
    private const val CAPACITY = 300
    private const val FILE_NAME = "web-trace.log"
    private val entries = ArrayDeque<String>()
    private val stamp = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    private val email = Regex("""[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+[.][A-Za-z]{2,}""")
    private val secret = Regex("""[A-Za-z0-9_-]{24,}""")
    @Volatile private var file: File? = null
    private var appended = 0
    private val writer: ExecutorService by lazy {
        Executors.newSingleThreadExecutor { task -> Thread(task, "web-trace").apply { isDaemon = true } }
    }

    private const val LINE = 160

    /** Never store an address or anything long enough to be a credential. */
    fun scrub(text: String, limit: Int = LINE): String = text
        .replace(email, "[email]")
        .replace(secret, "[redacted]")
        .replace(Regex("""\s+"""), " ")
        .trim()
        .take(limit)

    /** A detail too long for one line, scrubbed as a whole and kept as up to [maxLines] numbered lines. */
    fun recordLong(event: String, detail: String, maxLines: Int = 3) {
        val parts = scrub(detail, LINE * maxLines).chunked(LINE)
        parts.forEachIndexed { index, part -> record(if (parts.size > 1) "$event ${index + 1}/${parts.size}" else event, part) }
    }

    /**
     * Keeps the trace in [directory] as well as in memory. Scheduled refreshes run
     * while the app is closed and its process can be gone by the time someone
     * opens the diagnostics; without the file their lines were lost with it.
     */
    @Synchronized
    fun attach(directory: File) {
        if (file != null) return
        val target = File(directory, FILE_NAME)
        val stored = runCatching { if (target.exists()) target.readLines().filter(String::isNotBlank) else emptyList() }.getOrDefault(emptyList())
        val recent = entries.toList()
        entries.clear()
        (stored + recent).takeLast(CAPACITY).forEach(entries::addLast)
        file = target
        val copy = entries.toList()
        writer.execute { runCatching { target.writeText(copy.joinToString("\n", postfix = "\n")) } }
    }

    @Synchronized
    fun record(event: String, detail: String = "") {
        val line = buildString {
            append(stamp.format(Date()))
            append(' ')
            append(event)
            if (detail.isNotBlank()) {
                append(" · ")
                append(scrub(detail))
            }
        }
        entries.addLast(line)
        while (entries.size > CAPACITY) entries.removeFirst()
        val target = file ?: return
        // Append each line; once a capacity's worth has been added, rewrite the file to the kept lines.
        appended++
        val rewrite = if (appended >= CAPACITY) { appended = 0; entries.toList() } else null
        writer.execute {
            runCatching {
                if (rewrite != null) target.writeText(rewrite.joinToString("\n", postfix = "\n")) else target.appendText(line + "\n")
            }
        }
    }

    fun url(event: String, url: String, detail: String = "") =
        record(event, WebNavigationPolicy.redact(url) + if (detail.isBlank()) "" else " $detail")

    @Synchronized
    fun snapshot(): List<String> = entries.toList()

    @Synchronized
    fun clear() {
        entries.clear()
        appended = 0
        file?.let { target -> writer.execute { runCatching { target.writeText("") } } }
    }
}
