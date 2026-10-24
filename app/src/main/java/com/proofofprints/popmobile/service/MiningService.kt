/**
 * Android Foreground Service for mining.
 *
 * Keeps mining alive in the background with a persistent notification.
 * Manages the lifecycle of the native mining engine and stratum client.
 *
 * Copyright (c) 2026 OverBuild Labs
 */
package com.proofofprints.popmobile.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.net.wifi.WifiManager
import android.os.PowerManager
import android.util.Log
import com.proofofprints.popmobile.LogManager
import com.proofofprints.popmobile.R
import com.proofofprints.popmobile.mining.MiningEngine
import com.proofofprints.popmobile.popmanager.PoPManagerReporter
import com.proofofprints.popmobile.stratum.StratumClient
import com.proofofprints.popmobile.ui.FieldValidators
import com.proofofprints.popmobile.ui.MainActivity
import kotlinx.coroutines.*

class MiningService : Service(), StratumClient.StratumListener, MiningEngine.ShareCallback {

    companion object {
        private const val TAG = "MiningService"
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "kas_mining_channel"

        const val ACTION_START = "com.proofofprints.popmobile.START"
        const val ACTION_STOP = "com.proofofprints.popmobile.STOP"
        const val ACTION_ENSURE_RUNNING = "com.proofofprints.popmobile.ENSURE_RUNNING"

        /** Wake-lock timeout; statsUpdateLoop re-acquires at half of it. */
        private const val WAKE_LOCK_TIMEOUT_MS = 4 * 60 * 60 * 1000L
    }

    inner class MiningBinder : Binder() {
        fun getService(): MiningService = this@MiningService
    }

    private val binder = MiningBinder()
    private val miningEngine = MiningEngine()
    private val stratumClient = StratumClient(this)
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    lateinit var popManagerReporter: PoPManagerReporter
        private set
    private var miningStartedAt: Long = 0L
    private var wakeLock: PowerManager.WakeLock? = null
    private var wakeLockAcquiredAt = 0L
    private var statsJob: Job? = null
    private var reconnectJob: Job? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private lateinit var thermalMonitor: ThermalMonitor

    /** Public passthrough so the Settings UI can show a live thermal-zone
     *  diagnostic page without needing direct access to ThermalMonitor. */
    fun getThermalDiagnostics(): ThermalMonitor.ThermalDiagnostics =
        thermalMonitor.getDiagnostics()
    /** User preferences for thermal thresholds, external-power mode, etc.
     *  Shared with ThermalMonitor so both sides see the same config. */
    lateinit var preferences: MiningPreferences
        private set
    @Volatile private var isStopping: Boolean = false

    // Mining config
    var poolHost: String = ""
    var poolPort: Int = 0
    var walletAddress: String = ""
    var workerName: String = "OverMobile"
    var threadCount: Int = 2
    var currentDifficulty: Double = 0.0014  // Reasonable starting diff for ~40 KH/s phone

    // Thermal state exposed to UI. @Volatile because these are written by
    // the always-on thermal poller coroutine and read both by the UI and by
    // the stats-update loop on a different coroutine thread; without it the
    // stats loop can hold a stale `thermalState=NORMAL` in a register and
    // never enter the CRITICAL branch even though the poller has updated it.
    @Volatile var cpuTemp: Float = 0f
        private set
    @Volatile var batteryPercent: Int = 100
        private set
    @Volatile var isCharging: Boolean = false
        private set
    @Volatile var thermalState: ThermalMonitor.ThermalState = ThermalMonitor.ThermalState.NORMAL
        private set
    @Volatile var activeThreads: Int = 0
        private set
    /** Why the engine is paused within an active session, or null when it
     *  isn't. Each reason resumes only once its own condition clears (see
     *  [pauseBlocker]); a single shared flag used to let an unplugged pause
     *  "resume" after the thermal cool-down hold and re-pause in the same
     *  tick, every 30 s. */
    enum class PauseReason { HEAT, BATTERY, UNPLUGGED }
    @Volatile var pauseReason: PauseReason? = null
        private set
    private val thermalPaused: Boolean get() = pauseReason != null
    /** Heat-only severity from the poller (thermalState minus battery). */
    @Volatile private var heatState: ThermalMonitor.ThermalState = ThermalMonitor.ThermalState.NORMAL
    /** True once the current connection has delivered a job. A resume before
     *  that leaves the start to onNewJob instead of running the engine on a
     *  stale job from an earlier connection. */
    @Volatile private var hasJob: Boolean = false
    /** True once the engine has been started in the current session. The
     *  stats-update loop owns all start/stop transitions after the initial
     *  start; flipping this flag false on Stop / disconnect is what lets
     *  onNewJob start mining the very first time, and prevents it from
     *  racing the stats loop's stop+restart pattern (throttle / cooldown
     *  resume) by trying to bring the engine back up at full thread count. */
    @Volatile private var engineStartedOnce: Boolean = false
    /** True while the onDisconnected retry-loop coroutine is active. Guards
     *  against re-entrancy — StratumClient.connect() calls onDisconnected on
     *  failure, which would otherwise spawn a second retry loop that races
     *  with the first. */
    @Volatile private var isReconnecting: Boolean = false
    /** Latest DeviceStatus seen by the always-on thermal poller. Cached so
     *  the stats-update loop can recompute the protection banner immediately
     *  on pause transitions instead of waiting up to 3s for the next poller
     *  tick — otherwise a CRITICAL pause is invisible in the UI until temp
     *  has already dropped, at which point the banner shows 'cooling'
     *  instead of the actual cause. */
    @Volatile private var lastDeviceStatus: ThermalMonitor.DeviceStatus? = null
    /** Monotonic-ms timestamp of when displayTemp first dipped below the
     *  resume threshold after a pause. Used to hold off resuming until the
     *  drop has been sustained for [RESUME_HOLD_MS], preventing thrash. */
    private var coolingSince: Long = 0L
    /** How long the temperature must stay below resumeTempC before we'll
     *  bring mining back up from a thermal pause. */
    private val RESUME_HOLD_MS = 30_000L
    /** A battery pause resumes only above cutoff + this many percent, so the
     *  miner doesn't flap at the cutoff while charging. */
    private val BATTERY_RESUME_HYSTERESIS = 5

    /** Banner to show above the Start/Stop button so the user can tell at a
     *  glance why mining has been paused, throttled, or is running with
     *  reduced protections. Updated every tick. */
    enum class ProtectionSeverity { NONE, INFO, WARNING, CRITICAL }

    @Volatile var protectionMessage: String = ""
        private set
    @Volatile var protectionSeverity: ProtectionSeverity = ProtectionSeverity.NONE
        private set

    /** Last protection message we emitted to the log — stops us spamming
     *  LogManager on every 3-second tick. */
    private var lastLoggedProtectionMessage: String = ""

    // Stats exposed to UI — `hashrate` is the live (10s windowed) rate so the
    // user sees what the device is actually hashing at right now, not a lifetime
    // average that smears over throttling events.
    val hashrate: Double get() = miningEngine.hashrate10s
    val hashrate60s: Double get() = miningEngine.hashrate60s
    val hashrate15min: Double get() = miningEngine.hashrate15min
    val hashrateSessionAvg: Double get() = miningEngine.hashrate
    val totalHashes: Long get() = miningEngine.totalHashes
    val sharesFound: Int get() = miningEngine.sharesFound
    /** Shares the pool acknowledged this session. The native counter above
     *  counts shares found, including ones the pool later rejects or that
     *  are lost on a disconnect, so it overstates "accepted". */
    val sharesAccepted: Int get() = acceptedShares.get()
    private val acceptedShares = java.util.concurrent.atomic.AtomicInteger(0)
    val sharesRejected: Int get() = miningEngine.sharesRejected
    val isRunning: Boolean get() = miningEngine.isRunning
    val isPoolConnected: Boolean get() = stratumClient.isConnected

    enum class PoolState { DISCONNECTED, CONNECTING, CONNECTED, ERROR }

    /** Richer pool state for the UI — tracks whether we're mid-retry so the
     *  user can cancel a failing connection. */
    @Volatile var poolState: PoolState = PoolState.DISCONNECTED
        private set
    @Volatile var poolErrorReason: String? = null
        private set

    /** True when the user has tapped Start and we're actively trying to mine,
     *  even if the pool hasn't connected yet. Distinct from miningEngine.isRunning
     *  because the engine only starts after the first job arrives. */
    @Volatile var isSessionActive: Boolean = false
        private set

    // Listener for UI updates
    var onStatsUpdate: (() -> Unit)? = null
    var onShareSubmitted: (() -> Unit)? = null
    var onThermalWarning: ((ThermalMonitor.ThermalState, Float) -> Unit)? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        miningEngine.setShareCallback(this)
        preferences = MiningPreferences(this)
        thermalMonitor = ThermalMonitor(this, preferences)
        popManagerReporter = PoPManagerReporter(this, buildStatsProvider())
        popManagerReporter.setCommandExecutor(buildCommandExecutor())

        // Load saved mining config so telemetry has meaningful pool/worker values
        // even when the miner isn't actively running yet.
        val prefs = getSharedPreferences("kas_miner", Context.MODE_PRIVATE)
        PoolMigration.migrateSaved(prefs)
        val savedPoolUrl = prefs.getString("pool_url", "") ?: ""
        if (savedPoolUrl.isNotEmpty()) {
            val cleaned = savedPoolUrl.replace("stratum+tcp://", "")
            val parts = cleaned.split(":")
            if (parts.size == 2) {
                poolHost = parts[0]
                poolPort = parts[1].toIntOrNull() ?: 0
            }
        }
        walletAddress = prefs.getString("wallet", "") ?: ""
        workerName = prefs.getString("worker", "OverMobile") ?: "OverMobile"
        threadCount = prefs.getInt("threads", 2)

        // Start the reporter immediately — it runs independently of mining so
        // OverManager sees the device even when stopped/paused.
        popManagerReporter.start()

        // Always-on thermal poller — keeps cpuTemp / batteryPercent /
        // isCharging / thermalState fresh (and the protection banner up to
        // date) regardless of whether a mining session is active. Without
        // this, hitting Stop freezes the temperature display at its last
        // value and leaves the banner stuck on whatever it last showed.
        startThermalPoller()
    }

    private var thermalPollerJob: kotlinx.coroutines.Job? = null

    private fun startThermalPoller() {
        if (thermalPollerJob?.isActive == true) return
        thermalPollerJob = serviceScope.launch {
            while (isActive) {
                try {
                    val s = thermalMonitor.getStatus(threadCount)
                    cpuTemp = s.cpuTemp
                    batteryPercent = s.batteryPercent
                    isCharging = s.isCharging
                    thermalState = s.thermalState
                    heatState = s.heatState
                    lastDeviceStatus = s
                    updateProtectionBanner(s)
                    // Refresh the foreground notification so the temp / banner
                    // shown in the system tray stays accurate even when
                    // mining is stopped.
                    if (isSessionActive || protectionMessage.isNotEmpty()) {
                        try { updateNotification() } catch (_: Throwable) {}
                    }
                    onStatsUpdate?.invoke()
                } catch (t: Throwable) {
                    Log.e(TAG, "thermal poller failed: ${t.message}", t)
                }
                delay(3000)
            }
        }
    }

    /** Recompute [protectionMessage] / [protectionSeverity] from current
     *  state and emit a transition log line if anything changed. Called by
     *  the always-on thermal poller; the stats-update loop only needs to
     *  perform mining-side actions (throttle / pause / restore). */
    private fun updateProtectionBanner(status: ThermalMonitor.DeviceStatus?) {
        val (newSeverity, newMessage) = computeProtectionBanner(status)
        if (newMessage == protectionMessage) return
        val oldMessage = protectionMessage
        protectionMessage = newMessage
        protectionSeverity = newSeverity
        if (newMessage.isEmpty() && oldMessage.isEmpty()) return
        val transition = if (newMessage.isEmpty()) "cleared ($oldMessage)" else newMessage
        Log.i(TAG, "PROTECTION $transition")
        if (newMessage != lastLoggedProtectionMessage) {
            when (newSeverity) {
                ProtectionSeverity.CRITICAL,
                ProtectionSeverity.WARNING -> LogManager.warn("Protection: $transition")
                else -> LogManager.info("Protection: $transition")
            }
            lastLoggedProtectionMessage = newMessage
        }
    }

    private fun buildStatsProvider() = object : PoPManagerReporter.StatsProvider {
        override fun snapshot(): PoPManagerReporter.TelemetrySnapshot {
            // cpuTemp / batteryPercent / isCharging / thermalState are kept
            // current by the always-on thermal poller (started in onCreate),
            // so we just read them here — no need to re-sample on every
            // OverManager report.
            val runtime = if (miningStartedAt > 0)
                (System.currentTimeMillis() - miningStartedAt) / 1000 else 0L
            val status = when {
                thermalPaused -> "paused"
                miningEngine.isRunning -> "mining"
                else -> "stopped"
            }
            return PoPManagerReporter.TelemetrySnapshot(
                coin = "KAS",
                pool = if (poolHost.isNotEmpty()) "stratum+tcp://$poolHost:$poolPort" else "",
                worker = if (walletAddress.isNotEmpty()) "$walletAddress.$workerName" else workerName,
                // Report live 60s windowed rate — smooth enough to avoid jitter,
                // responsive enough to show thermal throttling as it happens.
                hashrate = miningEngine.hashrate60s,
                acceptedShares = sharesAccepted,
                rejectedShares = miningEngine.sharesRejected,
                difficulty = currentDifficulty,
                runtimeSeconds = runtime,
                cpuTemp = cpuTemp,
                throttleState = mapThrottleState(thermalState),
                batteryLevel = batteryPercent,
                batteryCharging = isCharging,
                threads = if (miningEngine.isRunning) activeThreads else threadCount,
                status = status
            )
        }
    }

    private fun buildCommandExecutor() = object : PoPManagerReporter.CommandExecutor {
        override fun execute(command: PoPManagerReporter.Command): String? {
            return try {
                when (command.type) {
                    "set_config" -> handleSetConfig(command.params)
                    "set_threads" -> handleSetThreads(command.params)
                    "start" -> handleRemoteStart()
                    "stop" -> handleRemoteStop()
                    "restart" -> handleRemoteRestart()
                    else -> "unknown command type: ${command.type}"
                }
            } catch (e: Exception) {
                Log.e(TAG, "Command ${command.type} threw: ${e.message}")
                e.message ?: "executor exception"
            }
        }
    }

    private fun handleSetConfig(params: com.google.gson.JsonObject): String? {
        val prefs = getSharedPreferences("kas_miner", Context.MODE_PRIVATE).edit()
        var changedPool = false
        var changedThreads = false

        // Validate every field before changing anything: these values arrive
        // over the network from OverManager, so apply the same checks the
        // Settings screen does (and refuse a half-applied config).
        val newPoolUrl = params.get("poolUrl")?.takeIf { !it.isJsonNull }?.asString?.trim()
        val newWallet = params.get("wallet")?.takeIf { !it.isJsonNull }?.asString?.trim()
        val newWorker = params.get("worker")?.takeIf { !it.isJsonNull }?.asString?.trim()
        newPoolUrl?.let { FieldValidators.validatePoolUrl(it)?.let { e -> return "invalid poolUrl: $e" } }
        newWallet?.let { FieldValidators.validateWalletAddress(it)?.let { e -> return "invalid wallet: $e" } }
        newWorker?.let { FieldValidators.validateWorkerName(it)?.let { e -> return "invalid worker: $e" } }
        val newThreads = params.get("threads")?.takeIf { !it.isJsonNull }?.asInt
        if (newThreads != null && newThreads !in 1..16) return "threads out of range: $newThreads"

        newPoolUrl?.let { url ->
            val cleaned = url.replace("stratum+tcp://", "").trimEnd('/')
            val parts = cleaned.split(":")
            if (parts.size != 2) return "invalid poolUrl: $url"
            val newHost = parts[0]
            val newPort = parts[1].toIntOrNull() ?: return "invalid port in poolUrl: $url"
            if (newHost != poolHost || newPort != poolPort) {
                poolHost = newHost
                poolPort = newPort
                changedPool = true
            }
            prefs.putString("pool_url", url)
        }
        newWallet?.let { wallet ->
            if (wallet != walletAddress) {
                // Payout address changed remotely — make it visible on the
                // device, not only in logcat.
                LogManager.warn("OverManager changed the payout wallet to ${wallet.take(14)}…${wallet.takeLast(6)}")
                walletAddress = wallet
                changedPool = true
            }
            prefs.putString("wallet", wallet)
        }
        newWorker?.let { w ->
            if (w != workerName) {
                workerName = w
                changedPool = true
            }
            prefs.putString("worker", w)
        }
        newThreads?.let { t ->
            if (t != threadCount) {
                threadCount = t
                changedThreads = true
            }
            prefs.putInt("threads", t)
        }
        prefs.apply()

        // Notify UI to refresh fields
        onStatsUpdate?.invoke()

        // If currently mining and the pool-side config changed, restart the
        // stratum connection + worker threads to pick up the new values.
        if (isSessionActive && changedPool) {
            Log.i(TAG, "set_config: restarting mining to apply new pool/wallet/worker")
            restartMiningInternal()
        } else if (miningEngine.isRunning && changedThreads) {
            // Threads-only change: reuse thermal restart path
            Log.i(TAG, "set_config: adjusting thread count to $threadCount")
            miningEngine.stop()
            miningEngine.start(threadCount)
            activeThreads = threadCount
        }

        return null
    }

    private fun handleSetThreads(params: com.google.gson.JsonObject): String? {
        val threads = params.get("threads")?.takeIf { !it.isJsonNull }?.asInt
            ?: return "missing threads param"
        if (threads < 1 || threads > 16) return "threads out of range: $threads"

        threadCount = threads
        getSharedPreferences("kas_miner", Context.MODE_PRIVATE)
            .edit().putInt("threads", threads).apply()
        onStatsUpdate?.invoke()

        if (miningEngine.isRunning) {
            Log.i(TAG, "set_threads: restarting engine with $threads threads")
            miningEngine.stop()
            miningEngine.start(threads)
            activeThreads = threads
        }
        return null
    }

    private fun handleRemoteStart(): String? {
        // A session that is connecting, waiting for a job or thermally paused
        // is already "started" even though the engine isn't running.
        if (isSessionActive) return null
        if (poolHost.isEmpty() || walletAddress.isEmpty()) {
            return "cannot start: pool or wallet not configured"
        }
        Log.i(TAG, "Remote start command")
        startMining()
        return null
    }

    private fun handleRemoteStop(): String? {
        if (!isSessionActive) return null  // already stopped = success
        Log.i(TAG, "Remote stop command")
        stopMining()  // keeps the reporter alive since serverUrl is set
        return null
    }

    private fun handleRemoteRestart(): String? {
        handleRemoteStop()
        Thread.sleep(500)
        return handleRemoteStart()
    }

    private fun restartMiningInternal() {
        serviceScope.launch {
            try {
                stratumClient.disconnect()
                miningEngine.stop()
                // disconnect() is intentional, so onDisconnected doesn't run;
                // reset here so onNewJob starts the engine on the new pool.
                engineStartedOnce = false
                hasJob = false
                activeThreads = 0
                poolState = PoolState.CONNECTING
                stratumClient.connect(poolHost, poolPort, walletAddress, workerName)
                // Mining engine will be restarted by the stratum job handler
            } catch (e: Exception) {
                Log.e(TAG, "restartMiningInternal failed: ${e.message}")
            }
        }
    }

    private fun mapThrottleState(state: ThermalMonitor.ThermalState): String = when (state) {
        ThermalMonitor.ThermalState.NORMAL -> "normal"
        ThermalMonitor.ThermalState.WARNING -> "light"
        ThermalMonitor.ThermalState.THROTTLE -> "moderate"
        ThermalMonitor.ThermalState.CRITICAL -> "critical"
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startMining()
            ACTION_STOP -> stopMining()
            ACTION_ENSURE_RUNNING -> ensureRunningForReporting()
        }
        return START_STICKY
    }

    /** Called when the app launches and OverManager is configured but mining
     *  isn't active. Promotes the service to foreground so the reporter stays
     *  alive when the app is backgrounded. */
    private fun ensureRunningForReporting() {
        if (miningEngine.isRunning) return  // already in foreground for mining
        Log.i(TAG, "ensureRunningForReporting: promoting to foreground")
        startForeground(NOTIFICATION_ID, createNotification("Idle — reporting to OverManager"))
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        Log.i(TAG, "Service onDestroy — tearing down reporter and mining")
        stratumClient.disconnect()
        miningEngine.stop()
        // Drops the native global ref to this Service so it can be collected.
        miningEngine.setShareCallback(null)
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        wifiLock?.let { if (it.isHeld) it.release() }
        wifiLock = null
        popManagerReporter.stop()
        serviceScope.cancel()
        super.onDestroy()
    }

    fun startMining() {
        // A second Start (double tap, or a remote start while connecting /
        // paused) would open a second pool connection, a second stats loop
        // fighting over throttling, and leak the first wake lock.
        if (isSessionActive) {
            Log.i(TAG, "startMining: session already active")
            return
        }
        // Become foreground first. From the background on Android 12+ this
        // throws; in that case leave nothing half-started.
        try {
            startForeground(NOTIFICATION_ID, createNotification("Connecting to pool..."))
        } catch (e: Exception) {
            Log.e(TAG, "startForeground failed: ${e.message}")
            LogManager.warn("Couldn't start mining in the background — open OverMobile and press Start")
            poolState = PoolState.ERROR
            poolErrorReason = "Open the app to start"
            return
        }

        Log.i(TAG, "Starting mining service...")
        LogManager.info("Mining service starting ($threadCount threads)")
        isStopping = false
        isSessionActive = true
        poolState = PoolState.CONNECTING
        poolErrorReason = null
        miningStartedAt = System.currentTimeMillis()
        miningEngine.resetStats()
        acceptedShares.set(0)
        // Reporter is already running from onCreate — just fire an immediate
        // status update so OverManager sees the transition quickly.
        popManagerReporter.reportNow()

        releaseLocks()
        // Acquire wake lock to prevent CPU sleep. Not reference counted, so
        // statsUpdateLoop can re-acquire it to extend the timeout for
        // sessions longer than WAKE_LOCK_TIMEOUT_MS.
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "KASMiner::MiningLock").apply {
            setReferenceCounted(false)
            acquire(WAKE_LOCK_TIMEOUT_MS)
        }
        wakeLockAcquiredAt = System.currentTimeMillis()

        // Acquire WiFi lock to prevent WiFi from being disabled during mining
        @Suppress("DEPRECATION")
        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        @Suppress("DEPRECATION")
        wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "KASMiner:WifiLock")
        wifiLock?.acquire()

        // Connect to pool then start mining
        statsJob?.cancel()
        statsJob = serviceScope.launch {
            try {
                // Start the stats loop first: it owns throttling and must run
                // even if the first connect fails and the retry loop takes over.
                launch { statsUpdateLoop() }
                stratumClient.connect(poolHost, poolPort, walletAddress, workerName)
            } catch (e: Exception) {
                val reason = friendlyConnectError(e)
                Log.e(TAG, "Failed to start mining: $reason")
                LogManager.warn("Pool connect failed: $reason")
                poolState = PoolState.ERROR
                poolErrorReason = reason
                updateNotification("Pool error: $reason")
            }
        }
    }

    /** Convert a network exception to a short, user-friendly reason string. */
    private fun friendlyConnectError(e: Throwable): String {
        val msg = e.message.orEmpty()
        return when {
            e is java.net.ConnectException && msg.contains("refused", true) -> "Refused"
            e is java.net.SocketTimeoutException -> "Timed out"
            e is java.net.UnknownHostException -> "DNS failed"
            msg.contains("Network is unreachable", true) -> "No network"
            msg.contains("refused", true) -> "Refused"
            msg.contains("timed out", true) -> "Timed out"
            msg.contains("unreachable", true) -> "Unreachable"
            else -> msg.take(40).ifEmpty { e.javaClass.simpleName }
        }
    }

    private fun releaseLocks() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        wifiLock?.let { if (it.isHeld) it.release() }
        wifiLock = null
    }

    fun stopMining() {
        Log.i(TAG, "Stopping mining...")
        isStopping = true
        isSessionActive = false
        poolState = PoolState.DISCONNECTED
        poolErrorReason = null
        miningStartedAt = 0L
        // Clear thermal-pause state so the protection banner doesn't keep
        // claiming "PAUSED — cooling" once the user has stopped on their own.
        pauseReason = null
        coolingSince = 0L
        activeThreads = 0
        engineStartedOnce = false
        hasJob = false
        statsJob?.cancel()
        statsJob = null
        reconnectJob?.cancel()
        reconnectJob = null
        // Close the socket before joining the workers: a worker blocked
        // writing a share to a dead connection would otherwise hang the join
        // (and the main thread with it).
        stratumClient.disconnect()
        miningEngine.stop()
        releaseLocks()

        // Keep the service alive so the OverManager reporter keeps running
        // and can receive remote commands. Update the foreground notification
        // to reflect the new state. If OverManager is NOT configured, there's
        // no reason to stay resident — tear the whole thing down.
        if (popManagerReporter.serverUrl.isNotEmpty()) {
            popManagerReporter.reportNow()
            updateNotificationForIdle()
            Log.i(TAG, "Service staying resident for OverManager reporting")
        } else {
            stopForeground(STOP_FOREGROUND_REMOVE)
            popManagerReporter.stop()
            stopSelf()
        }
    }

    /** Fully tear down the service — used on explicit user exit. */
    fun shutdown() {
        Log.i(TAG, "Shutting down mining service")
        stopMining()
        if (popManagerReporter.serverUrl.isNotEmpty()) {
            popManagerReporter.stop()
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun updateNotificationForIdle() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        nm.notify(NOTIFICATION_ID, createNotification("Idle — reporting to OverManager"))
    }

    private suspend fun statsUpdateLoop() {
        var tickCount = 0L
        var lastPerThreadHashes = LongArray(0)
        var lastPerThreadTickMs = 0L
        Log.i(TAG, "statsUpdateLoop ENTERED [${com.proofofprints.popmobile.mining.MiningEngine.BUILD_MARKER}] (engineRunning=${miningEngine.isRunning} paused=$thermalPaused session=$isSessionActive)")
        // Keyed on isSessionActive so the loop stays alive from pool-connect
        // (before the first job arrives and kicks off the miner) all the way
        // through thermal pauses, and only exits when the user stops the
        // session.
        //
        // Reads cpuTemp / batteryPercent / isCharging / thermalState from the
        // service properties — those are kept fresh by the always-on thermal
        // poller (started in onCreate). This loop only owns mining actions:
        // pause, throttle, restore, unplugged-pause, and the per-thread
        // hashrate diagnostic.
        while (isSessionActive) {
            tickCount++

            // Extend the wake lock before its timeout lapses on long sessions.
            val now = System.currentTimeMillis()
            if (now - wakeLockAcquiredAt > WAKE_LOCK_TIMEOUT_MS / 2) {
                wakeLock?.acquire(WAKE_LOCK_TIMEOUT_MS)
                wakeLockAcquiredAt = now
            }

            // Periodic hashrate diagnostic — every 20 ticks (~60s) log the
            // three windowed rates plus per-thread deltas. Lets us see in
            // logcat whether the drop is global (thermal) or one stuck worker
            // (scheduler/affinity). Wrapped in try/catch so a stale .so /
            // partial rebuild can't kill the loop.
            if (miningEngine.isRunning && tickCount % 20L == 0L) {
                try {
                    val h10 = miningEngine.hashrate10s
                    val h60 = miningEngine.hashrate60s
                    val h15m = miningEngine.hashrate15min
                    val hAvg = miningEngine.hashrate
                    val active = miningEngine.activeThreads
                    val nowMs = System.currentTimeMillis()
                    val currentPerThread = LongArray(active) { miningEngine.threadHashes(it) }

                    val perThreadRates = if (lastPerThreadHashes.size == active && lastPerThreadTickMs > 0) {
                        val dtSec = (nowMs - lastPerThreadTickMs) / 1000.0
                        if (dtSec > 0) {
                            (0 until active).joinToString(" ") { i ->
                                val delta = currentPerThread[i] - lastPerThreadHashes[i]
                                "t$i=${String.format(java.util.Locale.US, "%.0f", delta / dtSec)}"
                            }
                        } else ""
                    } else ""

                    Log.i(TAG, String.format(
                        java.util.Locale.US,
                        "HASHRATE 10s=%.0f 60s=%.0f 15m=%.0f avg=%.0f | temp=%.1fC thrStat=%s | %s",
                        h10, h60, h15m, hAvg, cpuTemp, thermalState.name, perThreadRates
                    ))
                    lastPerThreadHashes = currentPerThread
                    lastPerThreadTickMs = nowMs
                } catch (t: Throwable) {
                    Log.e(TAG, "HASHRATE diagnostic failed (loop continues): ${t.message}", t)
                }
            }

            // Pause / resume / throttle. A pause records its reason and
            // resumes only once every blocker is clear: a heat pause after a
            // sustained cool-down, battery and unplugged pauses right away.
            val heat = heatState
            val blocker = pauseBlocker(heat)
            val paused = pauseReason
            if (blocker != null) {
                coolingSince = 0L
                if (paused != blocker) {
                    pauseReason = blocker
                    updateProtectionBanner(lastDeviceStatus)
                }
                if (miningEngine.isRunning) {
                    Log.w(TAG, "Pausing mining: $blocker (${cpuTemp}°C, battery $batteryPercent%, charging=$isCharging)")
                    activeThreads = 0
                    // pauseReason is set before the stop so onNewJob (on the
                    // StratumClient thread) can't see a stopped, unpaused
                    // engine during the stop window and restart it.
                    miningEngine.stop()
                    if (!isSessionActive) return
                    // Refresh the banner now rather than on the next poller
                    // tick, which may already read a cooler temperature.
                    updateProtectionBanner(lastDeviceStatus)
                    onStatsUpdate?.invoke()
                    if (blocker == PauseReason.HEAT) onThermalWarning?.invoke(thermalState, cpuTemp)
                }
            } else if (paused != null) {
                val nowMs = System.currentTimeMillis()
                val ready = when {
                    paused != PauseReason.HEAT -> true
                    coolingSince == 0L -> {
                        coolingSince = nowMs
                        Log.i(TAG, "Cooling observed (${cpuTemp}°C ≤ ${preferences.resumeTempC}°C) — holding for ${RESUME_HOLD_MS / 1000}s before resume")
                        false
                    }
                    else -> nowMs - coolingSince >= RESUME_HOLD_MS
                }
                if (ready) {
                    if (!isSessionActive) return
                    Log.i(TAG, "Pause ($paused) cleared — resuming mining (${cpuTemp}°C, battery $batteryPercent%)")
                    LogManager.info("Mining resumed")
                    pauseReason = null
                    coolingSince = 0L
                    // No job on this connection yet: onNewJob starts the engine.
                    if (hasJob && !miningEngine.isRunning) {
                        miningEngine.start(threadCount)
                        activeThreads = threadCount
                        engineStartedOnce = true
                    }
                    updateProtectionBanner(lastDeviceStatus)
                    onStatsUpdate?.invoke()
                }
            } else when (heat) {
                ThermalMonitor.ThermalState.THROTTLE -> {
                    coolingSince = 0L
                    val recommended = maxOf(1, threadCount / 2)
                    if (miningEngine.isRunning && recommended < activeThreads) {
                        Log.w(TAG, "THERMAL THROTTLE (${cpuTemp}°C) — reducing to $recommended threads")
                        activeThreads = recommended
                        miningEngine.stop()
                        // nativeStop blocks until the worker threads exit,
                        // which can take seconds. If the user pressed Stop
                        // during that window, isSessionActive flipped false
                        // and we must NOT call start() again — otherwise the
                        // miner "fires back up" right after the user stopped.
                        if (!isSessionActive) return
                        miningEngine.start(recommended)
                        onThermalWarning?.invoke(thermalState, cpuTemp)
                    }
                }
                ThermalMonitor.ThermalState.NORMAL -> {
                    if (miningEngine.isRunning && activeThreads < threadCount && isCoolEnough()) {
                        val nowMs = System.currentTimeMillis()
                        if (coolingSince == 0L) {
                            coolingSince = nowMs
                            Log.i(TAG, "Cool enough to un-throttle (${cpuTemp}°C ≤ ${preferences.resumeTempC}°C, currently $activeThreads/$threadCount) — holding for ${RESUME_HOLD_MS / 1000}s")
                        } else if (nowMs - coolingSince >= RESUME_HOLD_MS) {
                            Log.i(TAG, "Restoring thread count $activeThreads → $threadCount (${cpuTemp}°C)")
                            miningEngine.stop()
                            if (!isSessionActive) return
                            miningEngine.start(threadCount)
                            activeThreads = threadCount
                            coolingSince = 0L
                        }
                    } else {
                        coolingSince = 0L
                    }
                }
                ThermalMonitor.ThermalState.WARNING -> {
                    coolingSince = 0L
                    onThermalWarning?.invoke(thermalState, cpuTemp)
                }
                // CRITICAL heat is always a blocker, handled above.
                ThermalMonitor.ThermalState.CRITICAL -> {}
            }

            delay(3000) // Check every 3 seconds
        }
        Log.i(TAG, "statsUpdateLoop EXITED (session=$isSessionActive)")
    }

    /** Devices with no readable sysfs zone (cpuTemp == 0) rely on the OS
     *  thermal signal; NORMAL from it is cool enough. */
    private fun isCoolEnough(): Boolean = cpuTemp <= 0f || cpuTemp <= preferences.resumeTempC

    /** The condition that should keep (or put) the engine paused right now,
     *  or null when mining may run. Order matches the banner's priority. */
    private fun pauseBlocker(heat: ThermalMonitor.ThermalState): PauseReason? {
        val current = pauseReason
        // Same scope as before the split: the battery cutoff only applies
        // with thermal protection on and outside external-power mode.
        val batteryGuard = preferences.thermalProtectionEnabled && !preferences.externalPowerMode
        val batteryResumeAt = preferences.batteryCutoffPercent +
            if (current == PauseReason.BATTERY) BATTERY_RESUME_HYSTERESIS else 0
        return when {
            batteryGuard && batteryPercent in 1..batteryResumeAt -> PauseReason.BATTERY
            !preferences.externalPowerMode && preferences.requireCharging && !isCharging ->
                PauseReason.UNPLUGGED
            heat == ThermalMonitor.ThermalState.CRITICAL -> PauseReason.HEAT
            current == PauseReason.HEAT &&
                (heat != ThermalMonitor.ThermalState.NORMAL || !isCoolEnough()) -> PauseReason.HEAT
            else -> null
        }
    }

    // ===== StratumListener callbacks =====

    override fun onConnected() {
        Log.i(TAG, "Pool connected!")
        LogManager.info("Pool connected successfully")
        poolState = PoolState.CONNECTED
        poolErrorReason = null
        updateNotification("Connected, waiting for job...")
    }

    override fun onDisconnected(reason: String) {
        Log.w(TAG, "Pool disconnected: $reason")
        LogManager.warn("Pool disconnected: $reason")
        val friendly = friendlyConnectError(RuntimeException(reason))
        poolState = if (isStopping) PoolState.DISCONNECTED else PoolState.ERROR
        poolErrorReason = if (isStopping) null else friendly
        miningEngine.stop()
        hasJob = false
        // Allow onNewJob to bring the engine back up after the reconnect
        // succeeds. Without this, engineStartedOnce stays true and the first
        // job after reconnect would just update setJob without restarting
        // the worker threads.
        engineStartedOnce = false
        updateNotification("Pool error: $friendly")

        if (isStopping) return
        // StratumClient.connect() itself calls listener.onDisconnected() on
        // failure, which would otherwise spawn a second retry coroutine and
        // race with the one already running. Gate on isReconnecting.
        if (isReconnecting) return
        isReconnecting = true

        // Reconnect with bounded exponential backoff, forever, while the
        // session is alive. Previously capped at 10 attempts (~7 min) which
        // meant a bridge that came back 10 minutes later would never be
        // picked up — the user had to manually Stop/Start to recover.
        reconnectJob = serviceScope.launch {
            try {
                var delayMs = 5_000L
                val maxDelayMs = 60_000L
                var attempt = 0
                while (!isStopping && !stratumClient.isConnected) {
                    attempt++
                    Log.i(TAG, "Reconnect attempt $attempt in ${delayMs / 1000}s...")
                    poolState = PoolState.CONNECTING
                    updateNotification("Reconnecting (attempt $attempt)...")
                    delay(delayMs)

                    if (isStopping) return@launch

                    try {
                        stratumClient.connect(poolHost, poolPort, walletAddress, workerName)
                        if (stratumClient.isConnected) {
                            Log.i(TAG, "Reconnected on attempt $attempt")
                            return@launch
                        }
                    } catch (e: Exception) {
                        val reason = friendlyConnectError(e)
                        Log.w(TAG, "Reconnect attempt $attempt failed: $reason")
                        poolState = PoolState.ERROR
                        poolErrorReason = reason
                    }

                    delayMs = minOf(delayMs * 2, maxDelayMs)
                }
            } finally {
                isReconnecting = false
            }
        }
    }

    override fun onNewJob(jobId: String, headerHash: ByteArray, timestamp: Long) {
        // Drop late jobs that arrive after the user has stopped the session.
        // Without this guard, a buffered job from the pool would re-start the
        // miner milliseconds after stopMining() returned, making the Stop
        // button appear unresponsive (the UI sees mining restart every few
        // hundred ms until the TCP disconnect actually propagates).
        if (!isSessionActive) {
            Log.i(TAG, "Ignoring job $jobId — session stopped")
            return
        }

        Log.i(TAG, "New job $jobId, applying difficulty=$currentDifficulty")
        LogManager.info("New job received: $jobId")
        val target = miningEngine.setTargetFromDifficulty(currentDifficulty)
        miningEngine.setJob(headerHash, jobId, target, timestamp)
        hasJob = true

        // Initial start only — once the engine is up for this session, the
        // stats-update loop owns all subsequent start/stop transitions
        // (throttle, pause, resume). Without this gate, an incoming job that
        // lands in the brief window between stats loop's stop() and start()
        // races and brings the engine back up at full thread count, undoing
        // the throttle (we'd see "reducing to 3 threads" but mining stays at
        // 6/6) or briefly resuming during a CRITICAL pause.
        if (!engineStartedOnce && !thermalPaused) {
            miningEngine.start(threadCount)
            activeThreads = threadCount
            engineStartedOnce = true
            LogManager.info("Mining started ($threadCount threads)")
            updateNotification("Mining...")
        }
    }

    override fun onDifficultyChanged(difficulty: Double) {
        val old = currentDifficulty
        currentDifficulty = difficulty
        Log.i(TAG, "Difficulty updated: $difficulty")
        LogManager.info("Difficulty: $old -> $difficulty")
    }

    override fun onShareAccepted() {
        Log.i(TAG, "Share accepted!")
        LogManager.info("Share accepted!")
        acceptedShares.incrementAndGet()
        onShareSubmitted?.invoke()
    }

    override fun onShareRejected(reason: String) {
        Log.w(TAG, "Share rejected: $reason")
        LogManager.warn("Share rejected: $reason")
        miningEngine.incrementRejected()
    }

    override fun onAuthorizeFailed(reason: String) {
        // Without this the miner hashed on with every share rejected and
        // nothing on screen said why. The connection stays up; the pool
        // decides whether to drop it.
        Log.w(TAG, "Pool rejected authorization: $reason")
        LogManager.error("Pool rejected wallet/worker: $reason")
        poolState = PoolState.ERROR
        poolErrorReason = "Wallet/worker rejected"
        updateNotification("Pool rejected wallet/worker")
    }

    override fun onExtranonceSet(extranonce: String, extranonceShifted: Long, extranonce2Bits: Int) {
        Log.i(TAG, "Extranonce set: $extranonce (prefix=0x${String.format("%016x", extranonceShifted)}, en2bits=$extranonce2Bits)")
        if (extranonce.isNotEmpty()) LogManager.info("Extranonce: $extranonce")
        miningEngine.setExtranonce(extranonceShifted, extranonce2Bits)
    }

    // ===== MiningEngine.ShareCallback =====

    override fun onShareFound(jobId: String, nonce: Long) {
        val nonceHex = String.format("%016x", nonce)
        Log.i(TAG, "Share found! Job: $jobId, Nonce: $nonce")
        LogManager.info("Share found! Nonce: $nonceHex Job: $jobId")
        // Called on a native hashing thread: hand the network write to IO so
        // a slow or dead socket never stalls hashing (or a stop()'s join).
        serviceScope.launch(Dispatchers.IO) {
            try { stratumClient.submitShare(jobId, nonce) } catch (e: Exception) {
                Log.e(TAG, "submitShare failed: ${e.message}")
            }
        }
    }

    // ===== Notification management =====

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "KAS Mining",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows mining status"
                setShowBadge(false)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    private fun createNotification(status: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, MiningService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE
        )

        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("OverMobile")
            .setContentText(status)
            .setSmallIcon(R.drawable.ic_stat_overmobile)
            .setContentIntent(pendingIntent)
            .addAction(Notification.Action.Builder(
                null, "Stop Mining", stopIntent
            ).build())
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(status: String? = null) {
        val thermalInfo = if (cpuTemp > 0) " | ${cpuTemp.toInt()}°C" else ""
        val protection = protectionMessage
            .takeIf { it.isNotEmpty() }
            ?.let { " · $it" } ?: ""
        val text = status ?: String.format(
            java.util.Locale.US,
            "%.2f H/s | A:%d R:%d%s%s",
            hashrate, sharesAccepted, sharesRejected, thermalInfo, protection
        )
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, createNotification(text))
    }

    /** Build the banner that appears above the Start/Stop button. Critical
     *  conditions (pause, battery cutoff, unplugged) win over informational
     *  ones (external-power mode, thermal protection disabled). Returns an
     *  empty message + NONE severity when mining is operating normally. */
    private fun computeProtectionBanner(
        status: ThermalMonitor.DeviceStatus?
    ): Pair<ProtectionSeverity, String> {
        val t = status?.cpuTemp ?: 0f
        val tStr = if (t > 0f) "${t.toInt()}°C" else "—"
        val batt = status?.batteryPercent ?: 0

        // --- Pause-driven: the stats loop records why it paused. ---
        pauseReason?.let { reason ->
            val thermalHigh = t > 0f && t >= preferences.pauseTempC
            val msg = when (reason) {
                PauseReason.BATTERY -> "PAUSED — battery $batt%"
                PauseReason.UNPLUGGED -> "PAUSED — unplugged"
                PauseReason.HEAT ->
                    if (thermalHigh) "PAUSED — thermal critical ($tStr)"
                    else "PAUSED — cooling ($tStr)"
            }
            return ProtectionSeverity.CRITICAL to msg
        }

        // --- Active protection (still mining but reduced) ---
        // CRITICAL handling: the thermal poller observes CRITICAL one tick
        // before the stats-update loop pauses the engine. Without this branch
        // the banner would be empty during that window — and by the time the
        // pause does land, the next poller tick already reads a cooler temp,
        // so the user only ever sees "PAUSED — cooling" and never the actual
        // "we just hit the limit" alert.
        if (status?.heatState == ThermalMonitor.ThermalState.CRITICAL) {
            return ProtectionSeverity.CRITICAL to "Critical temp ($tStr) — pausing"
        }
        if (miningEngine.isRunning && status?.heatState == ThermalMonitor.ThermalState.THROTTLE) {
            val active = miningEngine.activeThreads
            return ProtectionSeverity.WARNING to "THROTTLED — $active threads ($tStr)"
        }
        if (status?.heatState == ThermalMonitor.ThermalState.WARNING) {
            return ProtectionSeverity.WARNING to "Warm ($tStr) — monitoring"
        }

        // --- Informational (config-level deviations from safe defaults) ---
        if (!preferences.thermalProtectionEnabled) {
            return ProtectionSeverity.WARNING to "Thermal protection OFF"
        }
        // No usable thermal signal (sysfs locked AND headroom API absent).
        // Surface it — better to tell the user protection is operating blind
        // than silently mis-act on a missing reading. Pre-Android-11 devices
        // without readable sysfs zones land here.
        if (status?.thermalSensorAvailable == false) {
            return ProtectionSeverity.WARNING to "Thermal sensor unavailable"
        }
        // Battery Saver mode caps the CPU governor so the miner can't reach
        // its normal hashrate even when temps and battery are fine. Surface
        // it so users don't think the app itself is broken when Android is
        // the one throttling.
        if (isPowerSaveModeActive()) {
            return ProtectionSeverity.INFO to "Battery Saver active — OS throttling"
        }
        if (preferences.externalPowerMode) {
            return ProtectionSeverity.INFO to "External power mode"
        }

        return ProtectionSeverity.NONE to ""
    }

    private fun isPowerSaveModeActive(): Boolean = try {
        val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
        pm?.isPowerSaveMode == true
    } catch (_: Throwable) {
        false
    }

    private fun formatHashes(count: Long): String = when {
        count >= 1_000_000 -> String.format(java.util.Locale.US, "%.1fM", count / 1_000_000.0)
        count >= 1_000 -> String.format(java.util.Locale.US, "%.1fK", count / 1_000.0)
        else -> count.toString()
    }
}
