package me.balaj.inverterlink

import android.app.Application
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import org.json.JSONArray
import org.json.JSONObject

private val Application.preferences by preferencesDataStore("inverter-link")
private val Application.previewPreferences by preferencesDataStore("inverter-link-preview")

data class SavedDevice(val identity: String, val host: String?, val ip: String, val name: String) {
    val target
        get() = host ?: ip
}

data class Preferences(
    val devices: List<SavedDevice> = emptyList(),
    val defaultId: String = "",
    val openDefault: Boolean = false,
    val accent: String = "Mint",
    val refresh: Int = 5,
    val liveStatus: Boolean = false,
)

data class AppState(
    val preferences: Preferences = Preferences(),
    val selected: SavedDevice? = null,
    val discovered: List<String> = emptyList(),
    val scanning: Boolean = false,
    val connecting: Boolean = false,
    val connected: Boolean = false,
    val address: String? = null,
    val reading: Reading? = null,
    val metadata: Metadata? = null,
    val dashboard: Boolean = false,
    val settings: InverterSettings? = null,
    val settingsBusy: Boolean = false,
    val commandBusy: Boolean = false,
    val commandTitle: String = "",
    val commandResult: String = "",
    val error: String? = null,
    val settingsMessage: String? = null,
    val settingsError: Boolean = false,
    val preview: Boolean = false,
)

sealed interface SessionAction {
    data object Load : SessionAction

    data class Write(val setting: Setting, val value: String) : SessionAction

    data class Query(val command: ReadCommand) : SessionAction
}

class AppModel(application: Application, val preview: Boolean = false) :
    AndroidViewModel(application) {
    private val store = if (preview) application.previewPreferences else application.preferences
    private val mutable = MutableStateFlow(AppState(preview = preview))
    val state = mutable.asStateFlow()
    private var monitor: Job? = null
    private var scanJob: Job? = null
    @Volatile private var session: InverterSession? = null
    private val actions = Channel<SessionAction>(Channel.UNLIMITED)
    private val notifications = LiveStatus(application)
    private var foreground = true
    private var initialized = false
    @Volatile private var writePending = false
    @Volatile private var generation = 0
    private var scanGeneration = 0

    init {
        viewModelScope.launch {
            store.data
                .catch { emit(emptyPreferences()) }
                .collect { values ->
                    val prefs = decode(values)
                    mutable.update { it.copy(preferences = prefs) }
                    if (!initialized) {
                        initialized = true
                        if (preview) {
                            val sample =
                                prefs.devices.firstOrNull { it.identity == Sample.device.identity }
                                    ?: Sample.device
                            mutable.update {
                                it.copy(
                                    preferences =
                                        prefs.copy(
                                            devices = listOf(sample),
                                            defaultId = sample.identity,
                                        ),
                                    selected = sample,
                                    connected = true,
                                    address = sample.ip,
                                    reading = Sample.reading,
                                    metadata = Sample.metadata,
                                    settings = Sample.settings,
                                )
                            }
                        }
                        val initial = mutable.value.preferences
                        if (initial.openDefault)
                            initial.devices
                                .firstOrNull { it.identity == initial.defaultId }
                                ?.let { open(it) }
                    }
                }
        }
    }

    private fun decode(p: androidx.datastore.preferences.core.Preferences): Preferences {
        val devices = runCatching {
            val a = JSONArray(p[stringPreferencesKey("devices")] ?: "[]")
            (0 until a.length()).map { i ->
                val d = a.getJSONObject(i)
                SavedDevice(
                    d.getString("id"),
                    d.optString("host").takeIf { it.isNotBlank() },
                    d.getString("ip"),
                    d.getString("name"),
                )
            }
        }
            .getOrDefault(emptyList())
        val refresh = p[intPreferencesKey("refresh")] ?: 5
        return Preferences(
            devices,
            p[stringPreferencesKey("default")].orEmpty(),
            p[booleanPreferencesKey("openDefault")] ?: false,
            p[stringPreferencesKey("accent")] ?: "Mint",
            refresh.takeIf { it in listOf(5, 10, 15, 30, 60) } ?: 5,
            p[booleanPreferencesKey("liveStatus")] ?: false,
        )
    }

    fun preferences(change: (Preferences) -> Preferences) {
        val p = change(mutable.value.preferences)
        mutable.update { it.copy(preferences = p) }
        viewModelScope.launch {
            store.edit { v ->
                v[stringPreferencesKey("devices")] =
                    JSONArray()
                        .apply {
                            p.devices.forEach { d ->
                                put(
                                    JSONObject()
                                        .put("id", d.identity)
                                        .put("host", d.host.orEmpty())
                                        .put("ip", d.ip)
                                        .put("name", d.name)
                                )
                            }
                        }
                        .toString()
                v[stringPreferencesKey("default")] = p.defaultId
                v[booleanPreferencesKey("openDefault")] = p.openDefault
                v[stringPreferencesKey("accent")] = p.accent
                v[intPreferencesKey("refresh")] = p.refresh
                v[booleanPreferencesKey("liveStatus")] = p.liveStatus
            }
            updateNotification()
        }
    }

    fun clearError() {
        mutable.update { it.copy(error = null) }
    }

    fun rename(device: SavedDevice, name: String) {
        val n = name.trim()
        if (n.isEmpty() || n.length > 48 || n.any { it == '\n' || it == '\r' }) {
            mutable.update { it.copy(error = "Use a name of 1–48 characters on one line.") }
            return
        }
        preferences { p ->
            p.copy(
                devices =
                    p.devices.map { if (it.identity == device.identity) it.copy(name = n) else it }
            )
        }
        mutable.update {
            it.copy(
                selected =
                    it.selected?.let { d ->
                        if (d.identity == device.identity) d.copy(name = n) else d
                    }
            )
        }
    }

    fun forget(device: SavedDevice) {
        if (mutable.value.selected?.identity == device.identity) {
            disconnect()
            mutable.update { it.copy(selected = null) }
        }
        preferences { p ->
            val devices = p.devices.filter { it.identity != device.identity }
            p.copy(
                devices = devices,
                defaultId =
                    if (p.defaultId == device.identity) devices.firstOrNull()?.identity.orEmpty()
                    else p.defaultId,
                openDefault = p.openDefault && devices.isNotEmpty(),
            )
        }
    }

    fun scan() {
        if (preview) return
        cancelScan()
        mutable.update { it.copy(scanning = true, discovered = emptyList(), error = null) }
        val run = scanGeneration
        scanJob = viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val scope = WiFiScope.current(getApplication())
                    discover(scope, mutable.value.address.takeIf { mutable.value.connected }) { host
                        ->
                        mutable.update {
                            if (run == scanGeneration)
                                it.copy(discovered = (it.discovered + host).distinct().sorted())
                            else it
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (run == scanGeneration)
                    mutable.update { it.copy(error = e.message ?: "Discovery failed.") }
            } finally {
                if (run == scanGeneration) mutable.update { it.copy(scanning = false) }
            }
        }
    }

    fun cancelScan() {
        scanGeneration++
        scanJob?.cancel()
        scanJob = null
        mutable.update { it.copy(scanning = false) }
    }

    fun open(device: SavedDevice) {
        mutable.update { it.copy(dashboard = true) }
        cancelScan()
        if (mutable.value.connected && mutable.value.selected?.identity == device.identity) return
        connect(device.target, device)
    }

    fun connect(input: String, saved: SavedDevice? = null) {
        if (preview) {
            mutable.update {
                it.copy(
                    dashboard = true,
                    connected = true,
                    reading = Sample.reading,
                    settings = Sample.settings,
                    metadata = Sample.metadata,
                )
            }
            return
        }
        val host = runCatching {
            validateHost(input)
        }
            .getOrElse { e ->
                mutable.update { it.copy(error = e.message) }
                return
            }
        val previous = monitor
        stopMonitor()
        mutable.update {
            it.copy(
                dashboard = true,
                selected =
                    saved
                        ?: it.preferences.devices.firstOrNull { d ->
                            d.target == host || d.ip == host
                        },
                connecting = true,
                error = null,
            )
        }
        val run = generation
        monitor = viewModelScope.launch {
            previous?.join()
            withContext(Dispatchers.IO) {
                var owned: InverterSession? = null
                try {
                    val scope = WiFiScope.current(getApplication())
                    val selected = mutable.value.selected
                    var opened: InverterSession? = null
                    var ip: String? = null
                    val targets =
                        if (selected != null) listOfNotNull(selected.host, selected.ip).distinct()
                        else listOf(host)
                    var last: Exception? = null
                    for (target in targets) {
                        ensureActive()
                        try {
                            ip = scope.resolve(target)
                            opened = InverterSession.connect(scope, ip, selected?.identity)
                            break
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            last = e
                        }
                    }
                    if (opened == null && selected != null) {
                        val found = mutableListOf<String>()
                        discover(scope) { s -> synchronized(found) { found.add(s) } }
                        for (address in found) {
                            ensureActive()
                            try {
                                opened = InverterSession.connect(scope, address, selected.identity)
                                ip = address
                                break
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                last = e
                            }
                        }
                    }
                    val connection =
                        opened
                            ?: throw (last ?: IllegalStateException("Couldn't find your inverter."))
                    owned = connection
                    ensureActive()
                    session = connection
                    connection.use {
                        val metadata = connection.metadata
                        var first = true
                        while (isActive) {
                            val reading = connection.read()
                            ensureActive()
                            if (run != generation) break
                            mutable.update {
                                it.copy(
                                    reading = reading,
                                    metadata = metadata,
                                    address = ip,
                                    connected = true,
                                    connecting = false,
                                    error = null,
                                )
                            }
                            if (first) {
                                first = false
                                val existing =
                                    mutable.value.preferences.devices.firstOrNull {
                                        it.identity == metadata.identity
                                    }
                                val device =
                                    SavedDevice(
                                        metadata.identity,
                                        if (host.any { it.isLetter() }) host else selected?.host,
                                        ip!!,
                                        existing?.name
                                            ?: "${(metadata.power/1000).formatNumber()} kW inverter",
                                    )
                                withContext(Dispatchers.Main) {
                                    if (run != generation) return@withContext
                                    mutable.update { it.copy(selected = device) }
                                    preferences { p ->
                                        p.copy(
                                            devices =
                                                p.devices.filter {
                                                    it.identity != device.identity
                                                } + device,
                                            defaultId = p.defaultId.ifEmpty { device.identity },
                                        )
                                    }
                                }
                            }
                            withContext(Dispatchers.Main) { updateNotification() }
                            val end =
                                System.nanoTime() +
                                    mutable.value.preferences.refresh * 1_000_000_000L
                            while (isActive && System.nanoTime() < end) {
                                val action = actions.tryReceive().getOrNull()
                                if (action != null) process(connection, action, run) else delay(200)
                                connection.keepAlive()
                            }
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    ensureActive()
                    mutable.update {
                        it.copy(
                            connected = false,
                            connecting = false,
                            reading = null,
                            metadata = null,
                            settings = null,
                            settingsBusy = false,
                            commandBusy = false,
                            error = e.message ?: "Connection failed.",
                        )
                    }
                    withContext(Dispatchers.Main) { updateNotification() }
                } finally {
                    owned?.close()
                    if (session === owned) session = null
                }
            }
        }
    }

    private suspend fun process(s: InverterSession, a: SessionAction, run: Int) {
        try {
            when (a) {
                SessionAction.Load -> {
                    val loaded = s.settings()
                    mutable.update {
                        if (run == generation) it.copy(settings = loaded, settingsMessage = null)
                        else it
                    }
                }
                is SessionAction.Write -> {
                    val after = s.apply(a.setting, a.value)
                    if (run != generation) return
                    mutable.update {
                        it.copy(
                            settings = after,
                            settingsMessage = "${a.setting.title} saved and verified.",
                            settingsError = false,
                        )
                    }
                }
                is SessionAction.Query -> {
                    val result = s.query(a.command)
                    if (run != generation) return
                    mutable.update { it.copy(commandResult = result) }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            if (run != generation) return
            when (a) {
                is SessionAction.Query ->
                    mutable.update { it.copy(commandResult = e.message.orEmpty()) }
                else ->
                    mutable.update {
                        it.copy(settingsMessage = e.message.orEmpty(), settingsError = true)
                    }
            }
        } finally {
            if (run == generation) {
                if (a is SessionAction.Write) writePending = false
                mutable.update { it.copy(settingsBusy = false, commandBusy = false) }
            }
        }
    }

    fun loadSettings() {
        if (preview) return
        if (!mutable.value.connected || mutable.value.settingsBusy || mutable.value.commandBusy)
            return
        mutable.update { it.copy(settingsBusy = true, settingsMessage = null) }
        actions.trySend(SessionAction.Load)
    }

    fun apply(setting: Setting, value: String) {
        if (preview) return
        if (!mutable.value.connected || mutable.value.settingsBusy || mutable.value.commandBusy)
            return
        writePending = true
        mutable.update { it.copy(settingsBusy = true, settingsMessage = null) }
        actions.trySend(SessionAction.Write(setting, value))
    }

    fun query(command: ReadCommand) {
        if (preview) {
            mutable.update {
                it.copy(
                    commandTitle = command.label,
                    commandResult = Sample.replies[command.wire].orEmpty(),
                )
            }
            return
        }
        if (!mutable.value.connected || mutable.value.commandBusy || mutable.value.settingsBusy)
            return
        mutable.update {
            it.copy(commandBusy = true, commandTitle = command.label, commandResult = "")
        }
        actions.trySend(SessionAction.Query(command))
    }

    private fun stopMonitor() {
        generation++
        if (writePending) {
            writePending = false
            mutable.update {
                it.copy(
                    error =
                        "Setting verification was interrupted. A command may have been sent; check the inverter display before retrying."
                )
            }
        }
        monitor?.cancel()
        monitor = null
        session?.close()
        session = null
        while (actions.tryReceive().isSuccess) {}
        mutable.update {
            it.copy(
                connected = false,
                connecting = false,
                reading = null,
                metadata = null,
                settings = null,
                settingsBusy = false,
                commandBusy = false,
            )
        }
    }

    fun disconnect() {
        stopMonitor()
        notifications.end()
    }

    fun closeDashboard() {
        mutable.update { it.copy(dashboard = false) }
    }

    fun onForeground(active: Boolean) {
        foreground = active
        if (!active) {
            stopMonitor()
            updateNotification()
        } else if (mutable.value.dashboard && !mutable.value.connected && !mutable.value.connecting)
            mutable.value.selected?.let { open(it) }
    }

    private fun updateNotification() {
        val s = mutable.value
        if (!s.preferences.liveStatus) {
            notifications.end()
            return
        }
        notifications.update(
            s.selected?.name ?: "Inverter",
            s.reading,
            paused = !foreground || !s.connected,
        )
    }

    override fun onCleared() {
        stopMonitor()
        cancelScan()
        notifications.end()
    }
}

fun Double.formatNumber(): String =
    if (this % 1.0 == 0.0) toLong().toString() else "%.1f".format(java.util.Locale.ROOT, this)

object Sample {
    val device = SavedDevice("SAMPLE-LOGGER", null, "192.168.0.25", "Home inverter")
    val replies =
        mapOf(
            "QPI" to "PI30",
            "QVFW" to "VERFW:00080.01",
            "QPIRI" to
                "230.0 17.3 230.0 50.0 17.3 4000 4000 24.0 24.5 21.0 28.2 27.0 2 020 020 0 1 1 1 10 0 0 27.0 0 1",
            "QPIGS" to
                "221.8 49.8 221.8 49.8 0798 0764 019 403 27.00 000 100 0036 02.0 287.7 00.00 00000 00010110 00 00 00471 111 0 01 0000",
            "QMOD" to "L",
            "QPIWS" to "000000000000000000000000000000000000",
            "QFLAG" to "EabkxyzDdjlnuv",
            "QBEQI" to "0 060 030 020 030 27.00 000 120 0 0000",
            "QLED" to "1 0 3 0 2 000000255 000000255 148000255",
            "QMCHGCR" to "010 020 030 040 050 060 070 080 090 100 110 120",
            "QMUCHGCR" to "002 010 020 030 040 050 060 070 080 090 100",
        )
    val reading
        get() = Reading.parse(replies.getValue("QPIGS"), "L", replies.getValue("QPIWS"))

    val metadata
        get() = Metadata.parse(device.identity, replies.getValue("QVFW"), replies.getValue("QPIRI"))

    val settings
        get() =
            InverterSettings.parse(
                replies.getValue("QPIRI"),
                replies.getValue("QFLAG"),
                replies.getValue("QBEQI"),
                replies.getValue("QLED"),
                replies.getValue("QMCHGCR"),
                replies.getValue("QMUCHGCR"),
            )
}
