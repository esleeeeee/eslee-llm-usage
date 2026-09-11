package com.eslee.llmusage.core.web

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A short, shareable record of what the sign-in browser did, so a failure on a
 * phone this project cannot attach to a debugger can still be diagnosed from
 * evidence instead of guesswork.
 *
 * Only navigation shape and page classification are kept. URLs are reduced to
 * scheme, host and path, which drops OAuth codes and tokens carried in queries,
 * and free text is scrubbed of addresses and long secrets before it is stored.
 */
object WebTrace {
    private const val CAPACITY = 300
    private val entries = ArrayDeque<String>()
    private val stamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val email = Regex("""[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+[.][A-Za-z]{2,}""")
    private val secret = Regex("""[A-Za-z0-9_-]{24,}""")

    /** Never store an address or anything long enough to be a credential. */
    fun scrub(text: String): String = text
        .replace(email, "[email]")
        .replace(secret, "[redacted]")
        .replace(Regex("""\s+"""), " ")
        .trim()
        .take(160)

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
    }

    fun url(event: String, url: String, detail: String = "") =
        record(event, WebNavigationPolicy.redact(url) + if (detail.isBlank()) "" else " $detail")

    @Synchronized
    fun snapshot(): List<String> = entries.toList()

    @Synchronized
    fun clear() = entries.clear()
}
