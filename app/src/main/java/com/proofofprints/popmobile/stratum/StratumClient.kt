/**
 * Stratum protocol client for Kaspa pool communication.
 *
 * Ported from KASDeck Arduino stratum logic to Kotlin.
 * Handles mining.subscribe, mining.authorize, mining.notify, mining.submit.
 *
 * Copyright (c) 2026 OverBuild Labs
 */
package com.proofofprints.popmobile.stratum

import android.util.Log
import com.proofofprints.popmobile.BuildConfig
import com.proofofprints.popmobile.LogManager
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.*
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class StratumClient(
    private val listener: StratumListener
) {
    companion object {
        private const val TAG = "StratumClient"
        /** Longest stratum line we accept; real messages are well under 2 KB. */
        private const val MAX_LINE_CHARS = 64 * 1024
    }

    interface StratumListener {
        fun onConnected()
        fun onDisconnected(reason: String)
        fun onNewJob(jobId: String, headerHash: ByteArray, timestamp: Long)
        fun onDifficultyChanged(difficulty: Double)
        fun onShareAccepted()
        fun onShareRejected(reason: String)
        fun onExtranonceSet(extranonce: String, extranonceShifted: Long, extranonce2Bits: Int)
        fun onAuthorizeFailed(reason: String)
    }

    private var socket: Socket? = null
    private var writer: PrintWriter? = null
    private var reader: BufferedReader? = null
    private var readJob: Job? = null
    private val gson = Gson()
    // Shares are submitted from several threads; ids must stay unique so
    // accept/reject responses match the right submission.
    private val messageId = AtomicInteger(1)
    @Volatile private var walletWorker: String = ""
    private val pendingShareIds = java.util.Collections.synchronizedSet(mutableSetOf<Int>())
    @Volatile private var extranonce: String = ""  // Bridge-assigned extranonce prefix (hex chars)
    // Ids of this connection's subscribe/authorize requests, so their
    // responses can be told apart from each other and from share results.
    @Volatile private var subscribeId = -1
    @Volatile private var authorizeId = -1

    // java.net.Socket keeps reporting isConnected=true after the peer goes
    // away, so track liveness ourselves. connectionGen identifies the current
    // connection; a reader or writer from an older connection must not
    // report a disconnect for the new one.
    private val connected = AtomicBoolean(false)
    private val connectionGen = AtomicInteger(0)

    val isConnected: Boolean
        get() = connected.get()

    suspend fun connect(host: String, port: Int, wallet: String, worker: String) {
        withContext(Dispatchers.IO) {
            // Never leave an older socket and reader running alongside this one.
            disconnect()
            val gen = connectionGen.get()
            try {
                Log.i(TAG, "Connecting to $host:$port...")
                // Use explicit connect with 10s timeout so a refused/unreachable
                // pool surfaces a quick error instead of blocking for minutes.
                // Assigning the socket before connect() lets disconnect()
                // interrupt an in-progress connection attempt.
                val s = Socket()
                socket = s
                s.connect(java.net.InetSocketAddress(host, port), 10_000)
                s.keepAlive = true
                s.soTimeout = 300_000 // 5 minute read timeout
                writer = PrintWriter(s.getOutputStream(), true)
                reader = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8))
                if (gen != connectionGen.get()) return@withContext  // disconnected meanwhile

                // A new connection starts without the previous pool's
                // extranonce until (unless) this pool sends one.
                extranonce = ""
                listener.onExtranonceSet("", 0L, 64)

                connected.set(true)
                listener.onConnected()

                // Subscribe
                subscribeId = sendMessage("mining.subscribe", listOf(worker))
                delay(500)

                // Authorize
                // Public pools (e.g. HeroMiners) reject authorize without the
                // conventional password param; the OverBuild bridge ignores it.
                walletWorker = "$wallet.$worker"
                authorizeId = sendMessage("mining.authorize", listOf(walletWorker, "x"))

                // Start reading messages
                startReading(gen)

            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.e(TAG, "Connection failed: ${e.message}")
                connected.set(false)
                if (gen == connectionGen.get()) {
                    listener.onDisconnected(e.message ?: "Unknown error")
                }
            }
        }
    }

    /** Intentional close: does not call onDisconnected. */
    fun disconnect() {
        connectionGen.incrementAndGet()
        connected.set(false)
        readJob?.cancel()
        readJob = null
        closeStreams()
    }

    private fun closeStreams() {
        try {
            writer?.close()
            reader?.close()
            socket?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing connection: ${e.message}")
        }
        socket = null
        writer = null
        reader = null
        pendingShareIds.clear()
    }

    /** Unexpected loss of connection [gen]: report it once. */
    private fun connectionLost(gen: Int, reason: String) {
        if (gen != connectionGen.get()) return
        if (!connected.compareAndSet(true, false)) return
        Log.w(TAG, "Connection lost: $reason")
        closeStreams()
        listener.onDisconnected(reason)
    }

    fun submitShare(jobId: String, nonce: Long) {
        val fullNonceHex = String.format("%016x", nonce)

        // If bridge assigned an extranonce, send only the extranonce2 portion.
        // Bridge prepends extranonce1 to reconstruct the full nonce.
        val en = extranonce
        val nonceHex = if (en.isNotEmpty()) {
            "0x" + fullNonceHex.takeLast(16 - en.length)
        } else {
            "0x$fullNonceHex"
        }

        val submitId = messageId.getAndIncrement()
        pendingShareIds.add(submitId)
        // 3 params: wallet.worker, job_id, nonce_hex (with 0x prefix). Built
        // with Gson so a quote in a job id or worker name can't break the JSON.
        val msg = JsonObject().apply {
            addProperty("id", submitId)
            addProperty("method", "mining.submit")
            add("params", JsonArray().apply {
                add(walletWorker); add(jobId); add(nonceHex)
            })
        }
        sendRaw(gson.toJson(msg))
        Log.i(TAG, "Submitted share (id=$submitId) - Job: $jobId, Nonce: $nonceHex")
        LogManager.info("Share submitted - Job: $jobId, Nonce: $nonceHex")
    }

    /** Sends a request and returns its id. */
    private fun sendMessage(method: String, params: List<String>): Int {
        val id = messageId.getAndIncrement()
        val msg = JsonObject().apply {
            addProperty("id", id)
            addProperty("method", method)
            add("params", JsonArray().apply {
                params.forEach { add(it) }
            })
        }
        sendRaw(gson.toJson(msg))
        return id
    }

    @Synchronized
    private fun sendRaw(message: String) {
        val gen = connectionGen.get()
        val w = writer ?: return
        // PrintWriter swallows IOExceptions; checkError() is the only signal.
        w.println(message)
        if (w.checkError()) {
            connectionLost(gen, "Send failed")
            return
        }
        // Outgoing lines include the wallet (mining.authorize); keep them out
        // of release logcat.
        if (BuildConfig.DEBUG) Log.d(TAG, ">>> $message")
    }

    private fun startReading(gen: Int) {
        val r = reader ?: return
        readJob = CoroutineScope(Dispatchers.IO).launch {
            try {
                while (isActive) {
                    val line = readBoundedLine(r) ?: break
                    if (line.isBlank()) continue
                    if (BuildConfig.DEBUG) Log.d(TAG, "<<< $line")
                    handleMessage(line)
                }
                // readLine() returned null: the pool closed the connection.
                if (isActive) connectionLost(gen, "Pool closed the connection")
            } catch (e: Exception) {
                if (isActive) {
                    Log.e(TAG, "Read error: ${e.message}")
                    connectionLost(gen, e.message ?: "Read error")
                }
            }
        }
    }

    /** readLine() with a length cap, so a pool that never sends a newline
     *  can't grow one String until the app runs out of memory. Returns null
     *  at end of stream. */
    private fun readBoundedLine(r: BufferedReader): String? {
        val sb = StringBuilder()
        while (true) {
            val c = r.read()
            if (c == -1) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) return sb.toString().trimEnd('\r')
            if (sb.length >= MAX_LINE_CHARS) throw java.io.IOException("Pool sent an oversized line")
            sb.append(c.toChar())
        }
    }

    private fun handleMessage(json: String) {
        try {
            val doc = JsonParser.parseString(json).asJsonObject

            // Handle method calls from server (mining.notify, mining.set_difficulty)
            if (doc.has("method")) {
                val method = doc.get("method").asString

                when (method) {
                    "mining.notify" -> handleNotify(doc)
                    "mining.set_difficulty" -> handleSetDifficulty(doc)
                    // Some pools (HeroMiners) omit the "mining." prefix.
                    "mining.set_extranonce", "set_extranonce" -> handleSetExtranonce(doc)
                    else -> Log.w(TAG, "Unknown/unhandled method: $method — raw: $json")
                }
                return
            }

            // Handle responses to our requests
            if (doc.has("id")) {
                val id = doc.get("id").asInt

                if (pendingShareIds.remove(id)) {
                    // Share submission response
                    if (doc.has("error") && !doc.get("error").isJsonNull) {
                        val error = doc.get("error").toString()
                        Log.w(TAG, "Share rejected (id=$id): $error")
                        listener.onShareRejected(error)
                    } else if (doc.has("result")) {
                        val result = doc.get("result")
                        if (result.isJsonPrimitive && result.asBoolean) {
                            Log.i(TAG, "Share accepted! (id=$id)")
                            listener.onShareAccepted()
                        }
                    }
                } else {
                    Log.d(TAG, "Response for id=$id: ${doc}")
                    val error = doc.get("error")?.takeUnless { it.isJsonNull }
                    when (id) {
                        subscribeId -> handleSubscribeResult(doc)
                        authorizeId -> if (error != null) {
                            Log.w(TAG, "Authorize rejected: $error")
                            listener.onAuthorizeFailed(error.toString())
                        } else if (doc.get("result")?.let { it.isJsonPrimitive && !it.asBoolean } == true) {
                            listener.onAuthorizeFailed("login refused")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing message: ${e.message}")
        }
    }

    private fun handleNotify(doc: JsonObject) {
        try {
            val params = doc.getAsJsonArray("params")
            val jobId = params[0].asString

            // Parse header hash — can be array of uint64 or hex string
            val headerHash = ByteArray(32)

            val headerElement = params[1]
            if (headerElement.isJsonArray) {
                // Array of uint64 numbers (4 x uint64 = 32 bytes)
                // CRITICAL: Values can exceed Long.MAX_VALUE (unsigned uint64).
                // Gson's asLong falls back to (long)Double.parseDouble() for large values,
                // which corrupts the result. Must parse as string and use parseUnsignedLong.
                val headerArray = headerElement.asJsonArray
                for (i in 0 until minOf(4, headerArray.size())) {
                    // CRITICAL: Gson may return scientific notation for large numbers.
                    // Use BigInteger to handle any format correctly (decimal, scientific).
                    val bigVal = java.math.BigInteger(headerArray[i].asBigDecimal.toPlainString().split(".")[0])
                    val value = bigVal.toLong() // Returns low 64 bits — correct for unsigned u64
                    for (j in 0 until 8) {
                        headerHash[i * 8 + j] = ((value shr (j * 8)) and 0xFF).toByte()
                    }
                }
            } else {
                // Hex string format
                val hexStr = headerElement.asString
                for (i in 0 until minOf(32, hexStr.length / 2)) {
                    headerHash[i] = hexStr.substring(i * 2, i * 2 + 2).toInt(16).toByte()
                }
            }

            val timestamp = if (params.size() > 2) params[2].asLong else 0L

            // Log full parsed header hash for debugging
            val hashHex = headerHash.joinToString("") { "%02x".format(it) }
            if (BuildConfig.DEBUG) Log.d(TAG, "New job: $jobId, timestamp=$timestamp, headerHash=$hashHex")
            LogManager.info("Job: $jobId ts=$timestamp hash=${hashHex.take(16)}...")
            listener.onNewJob(jobId, headerHash, timestamp)

        } catch (e: Exception) {
            Log.e(TAG, "Error parsing mining.notify: ${e.message}")
        }
    }

    private fun handleSetDifficulty(doc: JsonObject) {
        try {
            val params = doc.getAsJsonArray("params")
            val difficulty = params[0].asDouble
            Log.i(TAG, "New difficulty: $difficulty")
            listener.onDifficultyChanged(difficulty)
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing mining.set_difficulty: ${e.message}")
        }
    }

    /** Some pools hand out the extranonce only in the subscribe result,
     *  shaped `[<subscriptions or null>, "<extranonce hex>", <en2 size>]`. */
    private fun handleSubscribeResult(doc: JsonObject) {
        val result = doc.get("result")?.takeIf { it.isJsonArray }?.asJsonArray ?: return
        if (result.size() < 2) return
        val en = result[1].takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString ?: return
        applyExtranonce(en)
    }

    private fun handleSetExtranonce(doc: JsonObject) {
        try {
            applyExtranonce(doc.getAsJsonArray("params")[0].asString)
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing mining.set_extranonce: ${e.message}")
        }
    }

    private fun applyExtranonce(en: String) {
        try {
            if (en.isEmpty()) return  // pool assigns no prefix: keep the full nonce space
            // Validate before adopting it: the native engine needs at least 8
            // free nonce bits (14 hex chars max), and submitShare's takeLast
            // throws on a prefix longer than 16.
            if (en.length > 14 || !en.all { it in '0'..'9' || it.lowercaseChar() in 'a'..'f' }) {
                Log.w(TAG, "Ignoring invalid extranonce '$en'")
                LogManager.warn("Pool sent an invalid extranonce; ignoring it")
                return
            }
            Log.i(TAG, "Extranonce set: '$en' (${en.length} hex chars = ${en.length / 2} bytes)")
            LogManager.info("Extranonce: $en")

            // Compute the extranonce value as upper nonce bytes for mining
            // e.g., extranonce "001e" → upper 2 bytes of every nonce must be 0x001e
            val extranonceValue = java.lang.Long.parseUnsignedLong(en, 16)
            val extranonce2Bits = (16 - en.length) * 4  // bits for miner portion
            val extranonceShifted = extranonceValue shl extranonce2Bits
            extranonce = en

            Log.i(TAG, "Extranonce nonce prefix: 0x${String.format("%016x", extranonceShifted)}, extranonce2 bits: $extranonce2Bits")
            listener.onExtranonceSet(extranonce, extranonceShifted, extranonce2Bits)
        } catch (e: Exception) {
            Log.e(TAG, "Error applying extranonce: ${e.message}")
        }
    }
}
