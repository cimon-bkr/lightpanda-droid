package app.lightpanda.droid

import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * A tiny SOCKS5 proxy on 127.0.0.1 that only Lightpanda uses (it needs the user name and password
 * made for this run). It exists because Lightpanda is a glibc program and glibc cannot look up host
 * names on Android; with this proxy the host names are sent here and resolved by Android itself.
 * The browser's TLS connections stay end to end: this only relays bytes.
 */
class Socks5Server(private val user: String, private val pass: String) {
    private var server: ServerSocket? = null

    val port: Int get() = server?.localPort ?: 0

    @Synchronized
    fun start() {
        if (server != null) return
        val s = ServerSocket(0, 64, InetAddress.getByName("127.0.0.1"))
        server = s
        thread(name = "socks5-accept", isDaemon = true) {
            while (!s.isClosed) {
                val client = try { s.accept() } catch (_: IOException) { break }
                thread(name = "socks5-conn", isDaemon = true) { handle(client) }
            }
        }
    }

    @Synchronized
    fun stop() {
        runCatching { server?.close() }
        server = null
    }

    private fun reply(out: OutputStream, code: Int) {
        out.write(byteArrayOf(5, code.toByte(), 0, 1, 0, 0, 0, 0, 0, 0))
        out.flush()
    }

    private fun handle(client: Socket) {
        var remote: Socket? = null
        try {
            client.soTimeout = 15_000
            val input = DataInputStream(client.getInputStream())
            val output = client.getOutputStream()

            if (input.readUnsignedByte() != 5) return
            val methods = ByteArray(input.readUnsignedByte())
            input.readFully(methods)
            if (!methods.contains(2.toByte())) {
                output.write(byteArrayOf(5, 0xFF.toByte()))
                return
            }
            output.write(byteArrayOf(5, 2))

            // Username/password (RFC 1929).
            if (input.readUnsignedByte() != 1) return
            val u = ByteArray(input.readUnsignedByte()).also { input.readFully(it) }
            val p = ByteArray(input.readUnsignedByte()).also { input.readFully(it) }
            if (String(u) != user || String(p) != pass) {
                output.write(byteArrayOf(1, 1))
                return
            }
            output.write(byteArrayOf(1, 0))

            // Request: only CONNECT is supported.
            val version = input.readUnsignedByte()
            val command = input.readUnsignedByte()
            input.readUnsignedByte()
            val type = input.readUnsignedByte()
            if (version != 5 || command != 1) {
                reply(output, 7)
                return
            }
            val host: String = when (type) {
                1 -> ByteArray(4).also { input.readFully(it) }.let { InetAddress.getByAddress(it).hostAddress!! }
                3 -> ByteArray(input.readUnsignedByte()).also { input.readFully(it) }.let { String(it) }
                4 -> ByteArray(16).also { input.readFully(it) }.let { InetAddress.getByAddress(it).hostAddress!! }
                else -> {
                    reply(output, 8)
                    return
                }
            }
            val port = input.readUnsignedShort()

            val upstream = Socket()
            remote = upstream
            try {
                upstream.connect(InetSocketAddress(host, port), 12_000)
            } catch (_: IOException) {
                reply(output, 5)
                return
            }
            reply(output, 0)
            client.soTimeout = 0

            val pump = thread(name = "socks5-up", isDaemon = true) {
                copy(client.getInputStream(), upstream.getOutputStream())
                runCatching { upstream.shutdownOutput() }
            }
            copy(upstream.getInputStream(), output)
            pump.join(2_000)
        } catch (_: IOException) {
        } finally {
            runCatching { remote?.close() }
            runCatching { client.close() }
        }
    }

    private fun copy(from: InputStream, to: OutputStream) {
        val buffer = ByteArray(16 * 1024)
        try {
            while (true) {
                val n = from.read(buffer)
                if (n < 0) break
                to.write(buffer, 0, n)
                to.flush()
            }
        } catch (_: IOException) {
        }
    }
}
