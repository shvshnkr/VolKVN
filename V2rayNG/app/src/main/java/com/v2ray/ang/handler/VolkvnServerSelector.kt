package com.v2ray.ang.handler

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.v2ray.ang.AngApplication
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.dto.ProfileItem
import com.v2ray.ang.enums.EConfigType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.math.roundToLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * VolKVN auto server selection: TCP probes + URL-test (Xray speedtest JSON) + composite ranking + fallback queue.
 * Ported from Husi [AutoServerSelector] adapted to MMKV / libv2ray.
 */
object VolkvnServerSelector {

    private const val TAG = "VolkvnServerSelector"
    private val prepareMutex = Mutex()
    @Volatile
    private var prepareGeneration = 0
    /** Cellular / DNS can be slow; too short → false negatives. */
    private const val PROBE_TIMEOUT_MS = 4000
    private const val FIRST_WAVE = 72
    private const val SECOND_WAVE = 72
    private const val MIN_ALIVE_AFTER_TWO_WAVES = 5
    private const val PARALLEL_PROBES = 20
    private const val TAIL_PROBE_MAX = 256
    private const val TAIL_PROBE_CHUNK = 48
    private const val TOP_RECHECK_COUNT = 10
    private const val TOP_RECHECK_ATTEMPTS = 3
    private const val UNHEALTHY_COOLDOWN_MS = 20 * 60 * 1000L

    private const val URL_TEST_CAP = 20
    private const val URL_TEST_EXTRA_TCP = 8
    private const val URL_TEST_SUPPLEMENT_CAP = 10
    private const val URL_TEST_PARALLELISM = 8

    private val unhealthyUntilMs = HashMap<String, Long>()

    @Synchronized
    fun markServerUnhealthy(guid: String?, reason: String) {
        if (guid.isNullOrBlank()) return
        unhealthyUntilMs[guid] = SystemClock.elapsedRealtime() + UNHEALTHY_COOLDOWN_MS
        Log.w(TAG, "Mark unhealthy guid=$guid cooldown=${UNHEALTHY_COOLDOWN_MS}ms reason=$reason")
    }

    @Synchronized
    private fun isServerOnCooldown(guid: String): Boolean {
        val until = unhealthyUntilMs[guid] ?: return false
        if (SystemClock.elapsedRealtime() >= until) {
            unhealthyUntilMs.remove(guid)
            return false
        }
        return true
    }

    /**
     * Clears fallback state and stores last-known-good after a successful connection (call on START_SUCCESS).
     */
    @Synchronized
    fun markConnected(guid: String?) {
        if (guid.isNullOrBlank()) return
        MmkvManager.setAutoSelectLastKnownGood(guid)
        MmkvManager.setAutoSelectFallbackQueue("")
        MmkvManager.setAutoSelectFallbackIndex(0)
    }

    /**
     * Advance to the next server in the prepared fallback queue. Returns new GUID or null if exhausted.
     */
    @Synchronized
    fun tryMoveToFallback(currentGuid: String?): String? {
        val queue = MmkvManager.getAutoSelectFallbackQueue()
            .split(",")
            .mapNotNull { it.trim().takeIf { s -> s.isNotEmpty() } }
        if (queue.isEmpty()) return null
        val rawIdx = currentGuid?.let { queue.indexOf(it) } ?: -1
        val currentIndex = if (rawIdx >= 0) {
            rawIdx
        } else {
            MmkvManager.getAutoSelectFallbackIndex().coerceIn(0, queue.indices.last.coerceAtLeast(0))
        }
        val nextIndex = currentIndex + 1
        if (nextIndex >= queue.size) return null
        val next = queue[nextIndex]
        MmkvManager.setAutoSelectFallbackIndex(nextIndex)
        MmkvManager.setSelectServer(next)
        SimpleModeStatusStore.setProgress(
            AngApplication.application,
            R.string.volkvn_status_switching_server,
            nextIndex + 1,
            queue.size,
            force = true,
        )
        Log.i(TAG, "Fallback advance to $next ($nextIndex/${queue.size})")
        return next
    }

    fun cancelInFlightPrepare() {
        prepareGeneration++
    }

    suspend fun prepareForConnect(
        context: Context,
        networkHandoff: Boolean = false,
    ): PrepareForConnectResult = prepareMutex.withLock {
        val generation = ++prepareGeneration
        try {
            VolkvnBuiltinBootstrap.ensureBuiltinHelpers(context)
            val whitelistBuiltinOnly = MmkvManager.isActiveWhitelistRestrictedNetwork() ||
                VolkvnSimpleModeNetwork.consumeWhitelistBuiltinPoolOnlyFlag()
            val guids = VolkvnServerPoolBuilder.buildCandidateGuids(whitelistBuiltinOnly)
            VolkvnDebugLog.simpleModeLog(
                "24",
                "prepare_pool wl=$whitelistBuiltinOnly handoff=$networkHandoff count=${guids.size}",
            )
            if (guids.isEmpty()) {
                return@withLock PrepareForConnectResult.NoProfiles
            }
            val forceReason = VolkvnAutoSelectProbePolicy.forceFullProbeReason(
                guids,
                whitelistBuiltinOnly,
                networkHandoff,
            )
            val lastKnown = MmkvManager.getAutoSelectLastKnownGood()
            if (forceReason == null && !networkHandoff && !lastKnown.isNullOrBlank() && lastKnown in guids) {
                if (isServerTcpHealthy(context, lastKnown, attempts = 2)) {
                    MmkvManager.setSelectServer(lastKnown)
                    VolkvnDebugLog.simpleModeLog("25", "prepare_skip_probe lastKnown=$lastKnown")
                    return@withLock PrepareForConnectResult.Success(lastKnown)
                }
            }
            if (forceReason != null) {
                VolkvnDebugLog.simpleModeLog("25", "full_probe_forced reason=$forceReason")
            }
            val pick = pickBestServerInternal(
                context,
                guids,
                whitelistBuiltinOnly,
                networkHandoff,
            )
            if (pick == null) {
                return@withLock PrepareForConnectResult.AllProbesDead
            }
            VolkvnAutoSelectProbePolicy.recordFullProbe(guids, whitelistBuiltinOnly)
            VolkvnAutoSelectProbePolicy.recordPrepareCompleted(guids)
            PrepareForConnectResult.Success(pick)
        } catch (e: CancellationException) {
            VolkvnDebugLog.simpleModeLog("31", "prepare_aborted")
            throw e
        }
    }

    private fun storedDelaySortKey(guid: String): Long {
        val delay = MmkvManager.decodeServerAffiliationInfo(guid)?.testDelayMillis ?: 0L
        return if (delay > 0L) delay else Long.MAX_VALUE
    }

    private fun tcpLatencyMs(host: String?, port: Int): Long {
        if (host.isNullOrBlank() || port <= 0) return Long.MAX_VALUE
        val start = SystemClock.elapsedRealtime()
        return try {
            Socket().use { s ->
                s.connect(InetSocketAddress(host, port), PROBE_TIMEOUT_MS)
            }
            SystemClock.elapsedRealtime() - start
        } catch (_: Exception) {
            Long.MAX_VALUE
        }
    }

    private fun probeRow(guid: String): Row? {
        val profile = MmkvManager.decodeServerConfig(guid) ?: return null
        if (profile.configType == EConfigType.CUSTOM || profile.configType == EConfigType.POLICYGROUP) {
            return null
        }
        val host = profile.server
        val port = profile.serverPort?.toIntOrNull() ?: return null
        val lat = tcpLatencyMs(host, port)
        return Row(guid, lat, profile)
    }

    private data class Row(val guid: String, val latency: Long, val profile: ProfileItem)

    private fun stableLatencyMs(profile: ProfileItem, attempts: Int): Long {
        val host = profile.server
        val port = profile.serverPort?.toIntOrNull() ?: return Long.MAX_VALUE
        if (attempts <= 1) return tcpLatencyMs(host, port)
        val samples = ArrayList<Long>(attempts)
        repeat(attempts) {
            val t = tcpLatencyMs(host, port)
            if (t < Long.MAX_VALUE) samples.add(t)
        }
        if (samples.isEmpty()) return Long.MAX_VALUE
        return samples.average().roundToLong()
    }

    private suspend fun refineTopCandidates(alive: List<Row>): List<Row> = withContext(Dispatchers.IO) {
        if (alive.size <= 1) return@withContext alive
        val head = alive.take(minOf(TOP_RECHECK_COUNT, alive.size))
        val refined = coroutineScope {
            head.map { row ->
                async(Dispatchers.IO) {
                    row.copy(latency = stableLatencyMs(row.profile, TOP_RECHECK_ATTEMPTS))
                }
            }.awaitAll()
        }
        val refinedAlive = refined.filter { it.latency < Long.MAX_VALUE }.sortedBy { it.latency }
        if (refinedAlive.isNotEmpty()) refinedAlive else alive
    }

    private suspend fun probeGuidsParallel(
        guids: List<String>,
        onChunkDone: ((probedInBatch: Int) -> Unit)? = null,
    ): List<Row> = withContext(Dispatchers.IO) {
        if (guids.isEmpty()) return@withContext emptyList()
        val out = ArrayList<Row>(guids.size)
        for (chunk in guids.chunked(PARALLEL_PROBES)) {
            val part = coroutineScope {
                chunk.map { guid ->
                    async(Dispatchers.IO) {
                        probeRow(guid)
                    }
                }.awaitAll().filterNotNull()
            }
            out.addAll(part)
            onChunkDone?.invoke(chunk.size)
        }
        out
    }

    suspend fun isServerTcpHealthy(context: Context, guid: String, attempts: Int = 2): Boolean = withContext(Dispatchers.IO) {
        val row = probeRow(guid)
        val profile = row?.profile
        val latency = when {
            row == null -> Long.MAX_VALUE
            attempts <= 1 -> row.latency
            profile == null -> Long.MAX_VALUE
            else -> stableLatencyMs(profile, attempts)
        }
        val healthy = latency < Long.MAX_VALUE
        VolkvnAgentDebug.emit(
            context,
            hypothesisId = "H12",
            location = "VolkvnServerSelector.kt:isServerTcpHealthy",
            message = "selected_health_check",
            data = mapOf(
                "guidLen" to guid.length,
                "healthy" to healthy,
                "latencyMs" to latency,
                "attempts" to attempts,
                "host" to (profile?.server ?: ""),
                "port" to (profile?.serverPort ?: ""),
            ),
        )
        healthy
    }

    private fun resolveCandidateGuids(subscriptionId: String): List<String> {
        return when (subscriptionId) {
            AppConfig.VOLKVN_SUBSCRIPTION_ID,
            AppConfig.VOLKVN_BUILTIN_HELPERS_SUBSCRIPTION_ID,
            -> VolkvnBuiltinBootstrap.mergePublicAndBuiltinGuids()
            else -> MmkvManager.decodeServerList(subscriptionId).toList()
        }
    }

    /**
     * Lower is better. URL latency dominates; otherwise TCP + synthetic penalty (Husi composite).
     */
    private fun compositeScore(
        guid: String,
        urlDelays: Map<String, Long>,
        tcpMap: Map<String, Long>,
    ): Long {
        val tcp = tcpMap[guid]?.takeIf { it < Long.MAX_VALUE }
        val url = urlDelays[guid]?.takeIf { it >= 0 }
        return when {
            url != null -> url
            tcp != null -> {
                val syntheticUrl = (tcp * 3).coerceIn(40, 900)
                10 * tcp + syntheticUrl
            }
            else -> Long.MAX_VALUE / 4
        }
    }

    private suspend fun measureRealDelay(context: Context, guid: String): Long = withContext(Dispatchers.IO) {
        val speedConfig = V2rayConfigManager.getV2rayConfig4Speedtest(context, guid)
        if (!speedConfig.status) return@withContext -1L
        V2RayNativeManager.initCoreEnv(context.applicationContext)
        val primary = V2RayNativeManager.measureOutboundDelay(
            speedConfig.content,
            SettingsManager.getDelayTestUrlForConnect(),
        )
        if (primary >= 0) return@withContext primary
        V2RayNativeManager.measureOutboundDelay(
            speedConfig.content,
            SettingsManager.getDelayTestUrlForConnect(true),
        )
    }

    private fun buildStratifiedUrlPool(
        guids: List<String>,
        cap: Int,
        priorityFirst: Set<String>,
    ): List<String> {
        if (guids.isEmpty() || cap <= 0) return emptyList()
        val bySub = guids.groupBy { MmkvManager.decodeServerConfig(it)?.subscriptionId.orEmpty() }
        val queues = bySub.mapValues { (_, list) ->
            list.sortedWith(
                compareBy<String> { if (it in priorityFirst) 0 else 1 },
            ).toMutableList()
        }
        val subOrder = queues.keys.sorted()
        val picked = ArrayList<String>(cap.coerceAtMost(guids.size))
        val used = HashSet<String>()
        var progress = true
        while (picked.size < cap && progress) {
            progress = false
            for (subId in subOrder) {
                if (picked.size >= cap) break
                val q = queues.getValue(subId)
                while (q.isNotEmpty() && q.first() in used) q.removeAt(0)
                val next = q.firstOrNull() ?: continue
                q.removeAt(0)
                picked += next
                used += next
                progress = true
            }
        }
        return picked
    }

    private suspend fun urlTestGuids(
        context: Context,
        guids: List<String>,
        onDone: ((done: Int, total: Int) -> Unit)? = null,
    ): Map<String, Long> = coroutineScope {
        if (guids.isEmpty()) return@coroutineScope emptyMap()
        val sem = Semaphore(URL_TEST_PARALLELISM)
        val out = mutableMapOf<String, Long>()
        val completed = java.util.concurrent.atomic.AtomicInteger(0)
        guids.map { guid ->
            async(Dispatchers.IO) {
                sem.withPermit {
                    val d = measureRealDelay(context, guid)
                    if (d >= 0) {
                        synchronized(out) { out[guid] = d }
                    }
                    val done = completed.incrementAndGet()
                    onDone?.invoke(done, guids.size)
                }
            }
        }.awaitAll()
        out.toMap()
    }

    /**
     * Picks best server using TCP waves + URL-test on top candidates, persists ranked fallback queue.
     */
    suspend fun pickBestServer(context: Context, subscriptionId: String) {
        V2RayNativeManager.initCoreEnv(context.applicationContext)
        VolkvnBuiltinBootstrap.ensureBuiltinHelpers(context.applicationContext)
        val subKey = subscriptionId.ifBlank { AppConfig.VOLKVN_SUBSCRIPTION_ID }
        val whitelistBuiltinOnly = MmkvManager.isActiveWhitelistRestrictedNetwork()
        val candidateGuids = when (subKey) {
            AppConfig.VOLKVN_SUBSCRIPTION_ID,
            AppConfig.VOLKVN_BUILTIN_HELPERS_SUBSCRIPTION_ID,
            -> VolkvnServerPoolBuilder.buildCandidateGuids(whitelistBuiltinOnly)
            else -> resolveCandidateGuids(subKey).distinct()
        }
        if (candidateGuids.isEmpty()) return
        pickBestServerInternal(context, candidateGuids, whitelistBuiltinOnly, networkHandoff = false)
    }

    /**
     * @return selected GUID or null if all probes failed
     */
    private suspend fun pickBestServerInternal(
        context: Context,
        candidateGuids: List<String>,
        whitelistBuiltinOnly: Boolean,
        networkHandoff: Boolean,
    ): String? {
        V2RayNativeManager.initCoreEnv(context.applicationContext)
        val priorityFirst = VolkvnServerPoolBuilder.priorityFirstIds(whitelistBuiltinOnly)

        val eligible = candidateGuids.filterNot { isServerOnCooldown(it) }
        val pool = if (eligible.isNotEmpty()) eligible else candidateGuids

        VolkvnAgentDebug.emit(
            context,
            hypothesisId = "H3",
            location = "VolkvnServerSelector.kt:pickBestServer:entry",
            message = "pool_sizes",
            data = mapOf(
                "guidsTotal" to candidateGuids.size,
                "eligibleNotCooldown" to eligible.size,
                "usingEligiblePool" to eligible.isNotEmpty(),
                "whitelistBuiltinOnly" to whitelistBuiltinOnly,
                "networkHandoff" to networkHandoff,
            ),
        )

        val builtinOrdered = VolkvnBuiltinBootstrap.stableGuidOrder().filter { it in pool }
        val restShuffled = pool.filter { it !in builtinOrdered.toSet() }.shuffled()
        val order = builtinOrdered + restShuffled
        val poolTotal = order.size

        var tcpProbed = 0
        fun reportTcpProgress(added: Int, force: Boolean = false) {
            tcpProbed = (tcpProbed + added).coerceAtMost(poolTotal)
            SimpleModeStatusStore.setProgress(
                context,
                R.string.volkvn_status_testing_tcp_progress,
                tcpProbed,
                poolTotal,
                force = force,
            )
        }

        SimpleModeStatusStore.setProgress(
            context,
            R.string.volkvn_status_testing_tcp_progress,
            0,
            poolTotal,
            force = true,
        )

        var rows = probeGuidsParallel(order.take(minOf(FIRST_WAVE, order.size))) { reportTcpProgress(it) }
        var alive = rows.filter { it.latency < Long.MAX_VALUE }.sortedBy { it.latency }

        if (alive.isEmpty() && order.size > FIRST_WAVE) {
            val rest = order.drop(FIRST_WAVE).take(minOf(SECOND_WAVE, order.size - FIRST_WAVE))
            rows = probeGuidsParallel(rest) { reportTcpProgress(it) }
            alive = rows.filter { it.latency < Long.MAX_VALUE }.sortedBy { it.latency }
        }
        if (alive.size < MIN_ALIVE_AFTER_TWO_WAVES && order.size > FIRST_WAVE + SECOND_WAVE) {
            val tailCandidates = order.drop(FIRST_WAVE + SECOND_WAVE)
                .sortedBy { storedDelaySortKey(it) }
                .take(TAIL_PROBE_MAX)
            var tailProbed = 0
            for (chunk in tailCandidates.chunked(TAIL_PROBE_CHUNK)) {
                if (alive.size >= MIN_ALIVE_AFTER_TWO_WAVES) break
                val tailRows = probeGuidsParallel(chunk) { reportTcpProgress(it) }
                tailProbed += chunk.size
                alive = (alive + tailRows.filter { it.latency < Long.MAX_VALUE }).sortedBy { it.latency }
            }
            if (tailCandidates.isNotEmpty()) {
                VolkvnDebugLog.simpleModeLog(
                    "25",
                    "tail_probe capped=${tailCandidates.size} probed=$tailProbed alive=${alive.size}",
                )
            }
        }
        reportTcpProgress(0, force = true)
        SimpleModeStatusStore.flushPendingProgress()
        if (alive.isNotEmpty()) {
            alive = refineTopCandidates(alive)
        }

        val tcpMap = HashMap<String, Long>()
        alive.forEach { tcpMap[it.guid] = it.latency }

        val urlTestCap = if (networkHandoff) 12 else URL_TEST_CAP
        val preUrlSorted = pool.sortedWith(
            compareBy<String> { if (it in priorityFirst) 0 else 1 }
                .thenBy { tcpMap[it] ?: Long.MAX_VALUE },
        )
        val baseUrlBatch = buildStratifiedUrlPool(preUrlSorted, urlTestCap, priorityFirst)
        val baseIds = baseUrlBatch.toSet()
        val extraTcpBatch = preUrlSorted
            .asSequence()
            .filter { it !in baseIds && (tcpMap[it] ?: Long.MAX_VALUE) < Long.MAX_VALUE }
            .take(if (networkHandoff) 4 else URL_TEST_EXTRA_TCP)
            .toList()
        val urlCandidates = (baseUrlBatch + extraTcpBatch).distinct()
        val urlTotal = urlCandidates.size.coerceAtLeast(1)
        SimpleModeStatusStore.setProgress(
            context,
            R.string.volkvn_status_testing_url_progress,
            0,
            urlTotal,
            force = true,
        )
        val urlDelays = urlTestGuids(context, urlCandidates) { done, total ->
            SimpleModeStatusStore.setProgress(
                context,
                R.string.volkvn_status_testing_url_progress,
                done,
                total,
            )
        }.toMutableMap()

        val missing = urlCandidates
            .filter { it !in urlDelays.keys }
            .take(if (networkHandoff) 6 else URL_TEST_SUPPLEMENT_CAP)
        if (missing.isNotEmpty()) {
            urlDelays.putAll(urlTestGuids(context, missing))
        }
        SimpleModeStatusStore.setProgress(
            context,
            R.string.volkvn_status_testing_url_progress,
            urlTotal,
            urlTotal,
            force = true,
        )
        SimpleModeStatusStore.flushPendingProgress()

        urlDelays.forEach { (g, d) ->
            if (d >= 0L) {
                MmkvManager.encodeServerTestDelayMillis(g, d)
            }
        }

        val lastKnown = MmkvManager.getAutoSelectLastKnownGood()

        val ranked = candidateGuids.sortedWith(
            compareBy<String> { compositeScore(it, urlDelays, tcpMap) }
                .thenBy { if (it == lastKnown) 0 else 1 }
                .thenBy { if (it in priorityFirst) 0 else 1 }
                .thenBy { candidateGuids.indexOf(it) },
        )

        if (alive.isEmpty() && urlDelays.isEmpty()) {
            Log.w(TAG, "All TCP and URL probes failed count=${candidateGuids.size} wl=$whitelistBuiltinOnly")
            VolkvnDebugLog.simpleModeLog("22", "prepare_all_probes_dead")
            return null
        }

        val pick = when {
            alive.isEmpty() -> {
                val validGuids = candidateGuids.filter { gid ->
                    val p = MmkvManager.decodeServerConfig(gid)
                    p != null && p.configType != EConfigType.CUSTOM && p.configType != EConfigType.POLICYGROUP
                }
                if (validGuids.isEmpty()) return null
                val guid = validGuids.random()
                val p = MmkvManager.decodeServerConfig(guid)!!
                Log.w(TAG, "No TCP probe succeeded; fallback random guid=$guid (${p.remarks})")
                guid
            }
            else -> ranked.first()
        }

        MmkvManager.setSelectServer(pick)
        if (alive.isEmpty()) {
            MmkvManager.setAutoSelectFallbackQueue("")
            MmkvManager.setAutoSelectFallbackIndex(0)
        } else {
            MmkvManager.setAutoSelectFallbackQueue(ranked.joinToString(","))
            MmkvManager.setAutoSelectFallbackIndex(0)
        }

        val p = MmkvManager.decodeServerConfig(pick)
        Log.i(
            TAG,
            "Selected ${p?.remarks} (${p?.server}:${p?.serverPort}) url=${urlDelays[pick]} tcp=${tcpMap[pick]}",
        )
        VolkvnAgentDebug.emit(
            context,
            hypothesisId = "H1",
            location = "VolkvnServerSelector.kt:pickBestServer:chosen",
            message = "final_pick",
            data = mapOf(
                "guidLen" to pick.length,
                "urlMs" to (urlDelays[pick] ?: -1L),
                "tcpMs" to (tcpMap[pick] ?: -1L),
                "queueSize" to ranked.size,
            ),
        )
        return pick
    }

}
