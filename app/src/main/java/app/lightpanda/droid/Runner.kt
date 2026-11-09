package app.lightpanda.droid

import android.content.Context
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.X509Certificate
import kotlin.concurrent.thread

/**
 * Starts Lightpanda. The official Linux build is a glibc program, which Android cannot run by
 * itself, so it is started through the glibc dynamic loader that ships with this app:
 *
 *     libglibcld.so --library-path <glibc dir> liblightpanda.so <arguments>
 *
 * The loader and Lightpanda are packaged as native libraries (the only place Android lets an app
 * run programs from); the glibc libraries are copied to the app's files folder on first start.
 */
object Runner {
    const val DEFAULT_PORT = 9222
    private const val MAX_LOG_LINES = 400

    val running = MutableStateFlow(false)
    val log = MutableStateFlow<List<String>>(emptyList())
    val port = MutableStateFlow(DEFAULT_PORT)

    private var process: Process? = null
    private var socks: Socks5Server? = null
    private val lock = Any()

    fun append(line: String) {
        val next = log.value + line
        log.value = if (next.size > MAX_LOG_LINES) next.takeLast(MAX_LOG_LINES) else next
    }

    private fun glibcDir(context: Context) = File(context.filesDir, "glibc")
    private fun loader(context: Context) = File(context.applicationInfo.nativeLibraryDir, "libglibcld.so")
    private fun program(context: Context) = File(context.applicationInfo.nativeLibraryDir, "liblightpanda.so")

    /** Copies the glibc libraries out of the APK and writes the CA bundle. Returns an error text or null. */
    private fun prepare(context: Context): String? {
        if (!loader(context).exists() || !program(context).exists()) {
            return "The app was built without Lightpanda or the glibc loader (this build is for 64-bit ARM phones)."
        }
        val dir = glibcDir(context)
        val stamp = File(dir, ".version")
        val version = BuildConfigVersion.code(context).toString()
        if (!stamp.exists() || stamp.readText() != version) {
            dir.deleteRecursively()
            dir.mkdirs()
            val names = context.assets.list("glibc") ?: emptyArray()
            if (names.isEmpty()) return "glibc libraries are missing from the app."
            for (name in names) {
                context.assets.open("glibc/$name").use { input ->
                    File(dir, name).outputStream().use { input.copyTo(it) }
                }
            }
            stamp.writeText(version)
        }
        return runCatching { writeCertificates(File(context.filesDir, "cacert.pem")) }.exceptionOrNull()?.let {
            "Could not read the system certificates: ${it.message}"
        }
    }

    /** Android's trusted certificates as one PEM file, which is what Lightpanda's --ca-cert wants. */
    private fun writeCertificates(target: File) {
        val store = KeyStore.getInstance("AndroidCAStore").apply { load(null) }
        val out = StringBuilder()
        for (alias in store.aliases()) {
            val cert = store.getCertificate(alias) as? X509Certificate ?: continue
            out.append("-----BEGIN CERTIFICATE-----\n")
            out.append(Base64.encodeToString(cert.encoded, Base64.NO_WRAP).chunked(64).joinToString("\n"))
            out.append("\n-----END CERTIFICATE-----\n")
        }
        target.writeText(out.toString())
    }

    private fun randomToken(): String {
        val bytes = ByteArray(16).also { SecureRandom().nextBytes(it) }
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun builder(context: Context, args: List<String>, proxyPort: Int, user: String, pass: String): ProcessBuilder {
        val command = listOf(
            loader(context).absolutePath,
            "--library-path", glibcDir(context).absolutePath,
            program(context).absolutePath,
        ) + args + listOf(
            "--http-proxy", "socks5h://$user:$pass@127.0.0.1:$proxyPort",
            "--ca-cert", File(context.filesDir, "cacert.pem").absolutePath,
        )
        val pb = ProcessBuilder(command).redirectErrorStream(true).directory(context.filesDir)
        val env = pb.environment()
        // Android's own library paths must not leak into a glibc program.
        env.remove("LD_LIBRARY_PATH")
        env.remove("LD_PRELOAD")
        env["HOME"] = context.filesDir.absolutePath
        env["TMPDIR"] = context.cacheDir.absolutePath
        env["LIGHTPANDA_DISABLE_TELEMETRY"] = "true"
        return pb
    }

    /** Starts the Chrome DevTools Protocol server on 127.0.0.1. Returns an error text or null. */
    fun startServer(context: Context, listenPort: Int): String? = synchronized(lock) {
        if (running.value) return@synchronized null
        val problem = prepare(context)
        if (problem != null) return@synchronized problem
        val user = "lp"
        val pass = randomToken()
        val proxy = Socks5Server(user, pass).also { it.start() }
        socks = proxy
        port.value = listenPort
        val p = try {
            builder(context, listOf("serve", "--host", "127.0.0.1", "--port", listenPort.toString()), proxy.port, user, pass).start()
        } catch (e: Exception) {
            proxy.stop()
            socks = null
            return@synchronized "Could not start Lightpanda: ${e.message}"
        }
        process = p
        running.value = true
        append("Started on ws://127.0.0.1:$listenPort")
        thread(name = "lightpanda-log", isDaemon = true) {
            p.inputStream.bufferedReader().useLines { lines -> lines.forEach { line -> append(line) } }
            val code = runCatching { p.waitFor() }.getOrDefault(-1)
            synchronized(lock) {
                if (process === p) {
                    process = null
                    socks?.stop()
                    socks = null
                    running.value = false
                }
            }
            append("Stopped (exit code $code)")
        }
        null
    }

    fun stop() = synchronized(lock) {
        process?.destroy()
    }

    /** Loads one page and returns what Lightpanda printed. */
    suspend fun fetch(context: Context, url: String, format: String): String = withContext(Dispatchers.IO) {
        val problem = prepare(context)
        if (problem != null) return@withContext problem
        val user = "lp"
        val pass = randomToken()
        val proxy = Socks5Server(user, pass).also { it.start() }
        try {
            val p = builder(context, listOf("fetch", "--dump", format, url), proxy.port, user, pass).start()
            val text = p.inputStream.bufferedReader().readText()
            val code = p.waitFor()
            val shown = if (text.length > 60_000) text.take(60_000) + "\n… (cut at 60,000 characters)" else text
            if (code == 0) shown else "Exit code $code\n$shown"
        } catch (e: Exception) {
            "Could not run Lightpanda: ${e.message}"
        } finally {
            proxy.stop()
        }
    }
}

/** The installed version code, used to refresh the copied glibc files after an update. */
object BuildConfigVersion {
    @Suppress("DEPRECATION")
    fun code(context: Context): Long =
        context.packageManager.getPackageInfo(context.packageName, 0).let {
            if (android.os.Build.VERSION.SDK_INT >= 28) it.longVersionCode else it.versionCode.toLong()
        }
}
