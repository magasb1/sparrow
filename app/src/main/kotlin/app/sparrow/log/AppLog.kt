package app.sparrow.log

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArraySet

/**
 * In-memory and on-disk application log (UI-5). Every line passes through [redact], which removes
 * registered secrets (SRT passphrases) and passphrase query parameters.
 */
object AppLog {
    private const val TAG = "Sparrow"
    private const val MAX_LINES = 2000
    private const val MAX_FILE_BYTES = 2L * 1024 * 1024

    private val lines = ArrayDeque<String>()
    private val secrets = CopyOnWriteArraySet<String>()
    private var file: File? = null
    private val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    fun init(context: Context) {
        file = File(context.filesDir, "sparrow.log").also {
            if (it.length() > MAX_FILE_BYTES) it.renameTo(File(context.filesDir, "sparrow.log.1"))
        }
    }

    /** Registers a secret that must never appear in logs. */
    fun addSecret(secret: String) {
        if (secret.length >= 4) secrets.add(secret)
    }

    fun redact(text: String): String {
        var out = text.replace(Regex("(?i)(passphrase=)[^&\\s]*"), "$1***")
        for (s in secrets) out = out.replace(s, "***")
        return out
    }

    fun i(message: String) = write('I', message, null)
    fun w(message: String, t: Throwable? = null) = write('W', message, t)
    fun e(message: String, t: Throwable? = null) = write('E', message, t)

    @Synchronized
    private fun write(level: Char, message: String, t: Throwable?) {
        val text = redact(message + (t?.let { ": ${it.javaClass.simpleName}: ${it.message}" } ?: ""))
        when (level) {
            'E' -> Log.e(TAG, text)
            'W' -> Log.w(TAG, text)
            else -> Log.i(TAG, text)
        }
        val line = "${time.format(Date())} $level $text"
        lines.addLast(line)
        while (lines.size > MAX_LINES) lines.removeFirst()
        runCatching { file?.appendText(line + "\n") }
    }

    @Synchronized
    fun snapshot(): List<String> = lines.toList()

    /** Writes the full log (previous file + current) to [target], redacted again on the way out. */
    @Synchronized
    fun exportTo(context: Context, target: File) {
        val previous = File(context.filesDir, "sparrow.log.1")
        target.parentFile?.mkdirs()
        target.bufferedWriter().use { w ->
            for (f in listOfNotNull(previous.takeIf { it.exists() }, file?.takeIf { it.exists() })) {
                f.forEachLine { w.write(redact(it)); w.newLine() }
            }
        }
    }
}
