@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class,
)

package me.balaj.inverterlink

import android.content.ClipData
import android.content.ClipboardManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.text.DateFormat
import java.util.Date

val accents =
    linkedMapOf(
        "Mint" to Color(0xFF47E3A8),
        "Blue" to Color(0xFF70A8FF),
        "Cyan" to Color(0xFF50DDE9),
        "Purple" to Color(0xFFC39BFF),
        "Pink" to Color(0xFFFF9BC4),
        "Orange" to Color(0xFFFFB472),
    )

@Composable
fun Symbol(
    id: Int,
    label: String?,
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
) {
    Icon(painterResource(id), label, modifier, tint = tint)
}

@Composable
fun Action(id: Int, label: String, enabled: Boolean = true, onClick: () -> Unit) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.semantics { contentDescription = label },
    ) {
        Symbol(id, label)
    }
}

@Composable
fun InverterApp(
    model: AppModel,
    localAction: (() -> Unit) -> Unit,
    enableStatus: (Boolean) -> Unit,
) {
    val s by model.state.collectAsStateWithLifecycle()
    val accent = accents[s.preferences.accent] ?: accents.getValue("Mint")
    MaterialExpressiveTheme(
        colorScheme =
            darkColorScheme(
                primary = accent,
                onPrimary = Color(0xFF072019),
                primaryContainer = accent.copy(alpha = 0.18f),
                onPrimaryContainer = accent,
                secondary = accent,
                secondaryContainer = accent.copy(alpha = 0.18f),
                onSecondaryContainer = accent,
                background = Color(0xFF0E1314),
                surface = Color(0xFF0E1314),
                surfaceContainer = Color(0xFF1B2224),
                surfaceContainerLow = Color(0xFF172021),
                surfaceContainerHighest = Color(0xFF30393B),
                surfaceContainerHigh = Color(0xFF252D2F),
            )
    ) {
        var root by rememberSaveable { mutableIntStateOf(0) }
        var page by rememberSaveable { mutableStateOf("dashboard") }
        var editor by rememberSaveable { mutableStateOf<String?>(null) }
        var address by remember { mutableStateOf(false) }
        var renaming by remember { mutableStateOf<SavedDevice?>(null) }
        var deviceMenu by remember { mutableStateOf<SavedDevice?>(null) }
        LaunchedEffect(s.dashboard) {
            if (!s.dashboard) {
                page = "dashboard"
                editor = null
            }
        }
        BackHandler(s.dashboard) {
            when {
                editor != null -> editor = null
                page != "dashboard" -> page = "dashboard"
                else -> model.closeDashboard()
            }
        }
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            if (!s.dashboard) if (root == 0) "Devices" else "App Settings"
                            else
                                editor?.let { id -> Setting.all.first { it.id == id }.title }
                                    ?: when (page) {
                                        "settings" -> "Inverter settings"
                                        "diagnostics" -> "Diagnostics"
                                        else -> s.selected?.name ?: "Inverter"
                                    }
                        )
                    },
                    navigationIcon = {
                        if (s.dashboard)
                            Action(R.drawable.ic_back, "Back") {
                                when {
                                    editor != null -> editor = null
                                    page != "dashboard" -> page = "dashboard"
                                    else -> model.closeDashboard()
                                }
                            }
                    },
                    actions = {
                        if (!s.dashboard && root == 0) {
                            Action(R.drawable.ic_wifi, if (s.scanning) "Cancel scan" else "Scan") {
                                if (s.scanning) model.cancelScan() else localAction { model.scan() }
                            }
                            Action(R.drawable.ic_add, "Connect by address") { address = true }
                        } else if (s.dashboard && page == "dashboard") {
                            s.selected?.let { device ->
                                Action(R.drawable.ic_more, "Device menu") { deviceMenu = device }
                            }
                        } else if (s.dashboard && page == "settings" && editor == null) {
                            Action(
                                R.drawable.ic_refresh,
                                "Refresh settings",
                                s.connected && !s.settingsBusy && !s.commandBusy && !s.preview,
                            ) {
                                model.loadSettings()
                            }
                            Action(R.drawable.ic_done, "Done") { page = "dashboard" }
                        } else if (s.dashboard && page == "diagnostics") {
                            Action(R.drawable.ic_done, "Done") { page = "dashboard" }
                        }
                    },
                )
            },
            bottomBar = {
                if (!s.dashboard)
                    NavigationBar {
                        NavigationBarItem(
                            selected = root == 0,
                            onClick = { root = 0 },
                            icon = { Symbol(R.drawable.ic_devices, null) },
                            label = { Text("Devices") },
                        )
                        NavigationBarItem(
                            selected = root == 1,
                            onClick = { root = 1 },
                            icon = { Symbol(R.drawable.ic_settings, null) },
                            label = { Text("Settings") },
                        )
                    }
            },
        ) { insets ->
            Box(Modifier.padding(insets).fillMaxSize()) {
                when {
                    s.dashboard && editor != null -> {
                        val setting = Setting.all.first { it.id == editor }
                        val settings = s.settings
                        if (settings != null && settings.raw(setting.source) != null)
                            SettingEditor(
                                setting,
                                settings,
                                s,
                                apply = { draft ->
                                    model.apply(setting, draft)
                                    editor = null
                                },
                            )
                        else {
                            editor = null
                        }
                    }
                    s.dashboard && page == "settings" ->
                        SettingsScreen(s, onEdit = { editor = it.id })
                    s.dashboard && page == "diagnostics" -> Diagnostics(s, model)
                    s.dashboard ->
                        Dashboard(
                            s,
                            onDetails = {
                                page = it
                                if (it == "settings" && s.settings == null) model.loadSettings()
                            },
                        )
                    root == 0 ->
                        Devices(
                            s,
                            onOpen = { localAction { model.open(it) } },
                            onDiscovered = { localAction { model.connect(it) } },
                            onMenu = { deviceMenu = it },
                        )
                    else -> AppSettings(s, model, enableStatus)
                }
            }
        }
        if (address)
            TextDialog(
                "Connect by address",
                "Hostname or private IP",
                s.selected?.target.orEmpty(),
                keyboard = KeyboardType.Ascii,
                onDismiss = { address = false },
                onSubmit = { value ->
                    address = false
                    localAction { model.connect(value) }
                },
            )
        renaming?.let { device ->
            TextDialog(
                "Rename inverter",
                "Name",
                device.name,
                onDismiss = { renaming = null },
                onSubmit = { name ->
                    model.rename(device, name)
                    renaming = null
                },
            )
        }
        deviceMenu?.let { device ->
            ModalBottomSheet(onDismissRequest = { deviceMenu = null }) {
                Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
                    Text(
                        device.name,
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.padding(16.dp),
                    )
                    MenuRow("Rename", R.drawable.ic_edit) {
                        deviceMenu = null
                        renaming = device
                    }
                    MenuRow("Use as default", R.drawable.ic_star) {
                        model.preferences { it.copy(defaultId = device.identity) }
                        deviceMenu = null
                    }
                    if (s.selected?.identity == device.identity && (s.connected || s.connecting))
                        MenuRow("Disconnect", R.drawable.ic_offline) {
                            model.disconnect()
                            deviceMenu = null
                        }
                    MenuRow("Forget", R.drawable.ic_delete) {
                        model.forget(device)
                        deviceMenu = null
                    }
                }
            }
        }
        s.error?.let { message ->
            val context = LocalContext.current
            AlertDialog(
                onDismissRequest = { model.clearError() },
                title = { Text("Connection") },
                text = {
                    SelectionContainer {
                        Text(message, Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState()))
                    }
                },
                dismissButton = {
                    TextButton(onClick = {
                        context.getSystemService(ClipboardManager::class.java)
                            .setPrimaryClip(ClipData.newPlainText("Inverter connection report", message))
                    }) { Text("Copy") }
                },
                confirmButton = { TextButton(onClick = { model.clearError() }) { Text("Done") } },
            )
        }
    }
}

@Composable
private fun MenuRow(text: String, icon: Int, click: () -> Unit) {
    ListItem(
        headlineContent = { Text(text) },
        leadingContent = { Symbol(icon, null) },
        modifier = Modifier.fillMaxWidth().clickable(onClick = click),
    )
}

@Composable
fun TextDialog(
    title: String,
    label: String,
    initial: String,
    keyboard: KeyboardType = KeyboardType.Text,
    onDismiss: () -> Unit,
    onSubmit: (String) -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                text,
                { text = it },
                label = { Text(label) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = keyboard),
                modifier = Modifier.testTag("dialogInput"),
            )
        },
        dismissButton = { Action(R.drawable.ic_close, "Cancel", onClick = onDismiss) },
        confirmButton = {
            Action(R.drawable.ic_done, "Done", text.isNotBlank()) { onSubmit(text) }
        },
    )
}

@Composable
fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 20.dp, bottom = 8.dp),
    )
}

@Composable
fun Group(content: @Composable ColumnScope.() -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(24.dp)) {
        Column(content = content)
    }
}

@Composable
fun DeviceRow(
    device: SavedDevice,
    default: Boolean,
    subtitle: String,
    onClick: () -> Unit,
    onMenu: () -> Unit,
) {
    ListItem(
        headlineContent = { Text(device.name) },
        supportingContent = { Text(subtitle) },
        leadingContent = { Symbol(R.drawable.ic_inverter, null) },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (default) Symbol(R.drawable.ic_star, "Default inverter", Modifier.size(18.dp))
                Action(R.drawable.ic_more, "Device menu", onClick = onMenu)
            }
        },
        colors =
            ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.combinedClickable(onClick = onClick, onLongClick = onMenu),
    )
}

@Composable
fun Devices(
    s: AppState,
    onOpen: (SavedDevice) -> Unit,
    onDiscovered: (String) -> Unit,
    onMenu: (SavedDevice) -> Unit,
) {
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
    ) {
        if (s.connected && s.selected != null) {
            item { SectionLabel("Connected") }
            item {
                Group {
                    DeviceRow(
                        s.selected,
                        s.preferences.defaultId == s.selected.identity,
                        s.selected.target,
                        { onOpen(s.selected) },
                        { onMenu(s.selected) },
                    )
                }
            }
        }
        val saved =
            s.preferences.devices.filter { !(s.connected && s.selected?.identity == it.identity) }
        if (saved.isNotEmpty()) {
            item { SectionLabel("Saved") }
            item {
                Group {
                    saved.forEach { device ->
                        DeviceRow(
                            device,
                            s.preferences.defaultId == device.identity,
                            device.target,
                            { onOpen(device) },
                            { onMenu(device) },
                        )
                    }
                }
            }
        }
        item { SectionLabel("Discovered") }
        item {
            Group {
                if (s.scanning)
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        LoadingIndicator(Modifier.size(32.dp))
                        Spacer(Modifier.width(12.dp))
                        Text("Scanning Wi-Fi…")
                    }
                if (s.discovered.isEmpty() && !s.scanning)
                    ListItem(
                        headlineContent = {
                            Text(
                                "Scan to find devices",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                        colors =
                            ListItemDefaults.colors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainer
                            ),
                    )
                s.discovered.forEach { ip ->
                    ListItem(
                        headlineContent = { Text(ip) },
                        leadingContent = { Symbol(R.drawable.ic_wifi, null) },
                        modifier = Modifier.clickable { onDiscovered(ip) },
                        colors =
                            ListItemDefaults.colors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainer
                            ),
                    )
                }
            }
        }
        item {
            Text(
                if (s.preview) "Preview · sample values · no network writes"
                else "Local mode may pause Energy Mate.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
        }
    }
}

@Composable
fun Dashboard(s: AppState, onDetails: (String) -> Unit) {
    var details by rememberSaveable { mutableStateOf(false) }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Group {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            s.metadata?.power?.let { "${(it/1000).formatNumber()} kW inverter" }
                                ?: "Inverter",
                            style = MaterialTheme.typography.titleLarge,
                        )
                        Text(
                            if (s.preview) "Preview · sample values"
                            else if (s.connecting) "Connecting…"
                            else s.reading?.mode ?: "Inverter offline",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        s.reading?.let {
                            Text(
                                DateFormat.getTimeInstance(DateFormat.MEDIUM).format(Date(it.time)),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    if (s.connecting) LoadingIndicator(Modifier.size(32.dp))
                    else
                        Symbol(if (s.connected) R.drawable.ic_wifi else R.drawable.ic_offline, null)
                }
            }
        }
        item {
            Card(
                shape = RoundedCornerShape(28.dp),
                modifier = Modifier.fillMaxWidth().height(300.dp),
            ) {
                SolarHouse(s.reading, Modifier.fillMaxSize())
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Metric(
                    "Solar",
                    s.reading?.values?.get("PV charging power"),
                    "W",
                    R.drawable.ic_sun,
                    Modifier.weight(1f),
                )
                Metric(
                    "Load",
                    s.reading?.values?.get("Load power"),
                    "W",
                    R.drawable.ic_bolt,
                    Modifier.weight(1f),
                )
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Metric(
                    "Battery",
                    s.reading?.values?.get("Reported battery"),
                    "%",
                    R.drawable.ic_battery,
                    Modifier.weight(1f),
                    true,
                )
                Metric(
                    "Grid",
                    s.reading?.values?.get("Grid voltage"),
                    "V",
                    R.drawable.ic_bolt,
                    Modifier.weight(1f),
                    true,
                )
                Metric(
                    "Temp",
                    s.reading?.values?.get("Temperature"),
                    "°C",
                    R.drawable.ic_temperature,
                    Modifier.weight(1f),
                    true,
                )
            }
        }
        if (s.reading?.hasWarning == true)
            item {
                Text(
                    "Inverter warnings: ${s.reading.warnings}",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        item {
            Group {
                ListItem(
                    headlineContent = { Text("Details") },
                    trailingContent = { Symbol(R.drawable.ic_expand, null) },
                    modifier = Modifier.clickable { details = !details },
                    colors =
                        ListItemDefaults.colors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainer
                        ),
                )
                if (details) {
                    MenuRow("Inverter settings", R.drawable.ic_settings) { onDetails("settings") }
                    MenuRow("Diagnostics", R.drawable.ic_info) { onDetails("diagnostics") }
                    s.metadata?.let {
                        ListItem(
                            headlineContent = { Text("Firmware") },
                            supportingContent = { Text(it.firmware) },
                        )
                    }
                    s.reading?.values?.forEach { (name, value) ->
                        ListItem(
                            headlineContent = { Text(name) },
                            trailingContent = {
                                Text("${value.formatNumber()} ${Reading.units[name].orEmpty()}")
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Metric(
    label: String,
    value: Double?,
    unit: String,
    icon: Int,
    modifier: Modifier,
    compact: Boolean = false,
) {
    Surface(
        modifier,
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(24.dp),
    ) {
        Column(
            Modifier.padding(if (compact) 12.dp else 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Symbol(icon, null, Modifier.size(16.dp))
                Text(label, style = MaterialTheme.typography.labelMedium)
            }
            Text(
                "${value?.formatNumber()?:"—"} $unit",
                style =
                    if (compact) MaterialTheme.typography.titleMedium
                    else MaterialTheme.typography.headlineMedium,
                maxLines = 1,
            )
        }
    }
}

@Composable
fun AppSettings(s: AppState, model: AppModel, enableStatus: (Boolean) -> Unit) {
    var refreshMenu by remember { mutableStateOf(false) }
    var defaultMenu by remember { mutableStateOf(false) }
    LazyColumn(contentPadding = PaddingValues(16.dp)) {
        item {
            Group {
                ListItem(
                    headlineContent = { Text("Refresh interval") },
                    trailingContent = {
                        Box {
                            TextButton(onClick = { refreshMenu = true }) {
                                Text("${s.preferences.refresh}s")
                            }
                            DropdownMenu(refreshMenu, { refreshMenu = false }) {
                                listOf(5, 10, 15, 30, 60).forEach { seconds ->
                                    DropdownMenuItem(
                                        text = { Text("${seconds}s") },
                                        onClick = {
                                            model.preferences { it.copy(refresh = seconds) }
                                            refreshMenu = false
                                        },
                                    )
                                }
                            }
                        }
                    },
                )
                ListItem(
                    headlineContent = { Text("Live status notification") },
                    trailingContent = { Switch(s.preferences.liveStatus, enableStatus) },
                )
            }
        }
        item { SectionLabel("Appearance") }
        item {
            Group {
                Column(Modifier.padding(16.dp)) {
                    Text("Accent color", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(12.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        accents.forEach { (name, color) ->
                            Box(
                                Modifier.size(48.dp)
                                    .clip(CircleShape)
                                    .background(color)
                                    .clickable { model.preferences { it.copy(accent = name) } }
                                    .semantics {
                                        contentDescription = "$name accent"
                                        selected = s.preferences.accent == name
                                    },
                                contentAlignment = Alignment.Center,
                            ) {
                                if (s.preferences.accent == name)
                                    Symbol(
                                        R.drawable.ic_done,
                                        null,
                                        Modifier.size(24.dp),
                                        tint = Color.Black,
                                    )
                            }
                        }
                    }
                }
            }
        }
        item { SectionLabel("Launch") }
        item {
            Group {
                ListItem(
                    headlineContent = { Text("Open default inverter") },
                    trailingContent = {
                        Switch(
                            s.preferences.openDefault,
                            { v -> model.preferences { it.copy(openDefault = v) } },
                            enabled = s.preferences.devices.isNotEmpty(),
                        )
                    },
                )
                if (s.preferences.devices.isNotEmpty()) {
                    ListItem(
                        headlineContent = { Text("Default inverter") },
                        trailingContent = {
                            Box {
                                TextButton(onClick = { defaultMenu = true }) {
                                    Text(
                                        s.preferences.devices
                                            .firstOrNull { it.identity == s.preferences.defaultId }
                                            ?.name ?: "Choose"
                                    )
                                }
                                DropdownMenu(defaultMenu, { defaultMenu = false }) {
                                    s.preferences.devices.forEach { device ->
                                        DropdownMenuItem(
                                            text = { Text(device.name) },
                                            onClick = {
                                                model.preferences {
                                                    it.copy(defaultId = device.identity)
                                                }
                                                defaultMenu = false
                                            },
                                        )
                                    }
                                }
                            }
                        },
                    )
                }
            }
        }
        item {
            Text(
                "Monitoring pauses when the app is backgrounded.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
        }
    }
}

@Composable
fun SettingsScreen(s: AppState, onEdit: (Setting) -> Unit) {
    var category by rememberSaveable { mutableStateOf(Category.OUTPUT) }
    if (s.settings == null && !s.settingsBusy) {
        EmptyState(
            if (s.connected) "Settings not loaded" else "Inverter offline",
            if (s.connected) R.drawable.ic_settings else R.drawable.ic_offline,
        )
        return
    }
    Column(Modifier.fillMaxSize()) {
        if (s.settings != null)
            Row(
                Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Category.entries.forEach { c ->
                    FilterChip(category == c, { category = c }, label = { Text(c.title) })
                }
            }
        LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (s.settingsBusy)
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        LoadingIndicator(Modifier.size(32.dp))
                        Text("Reading settings…")
                    }
                }
            s.settingsMessage?.let { message ->
                item {
                    Text(
                        message,
                        color =
                            if (s.settingsError) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.primary,
                    )
                }
            }
            s.settings?.let { settings ->
                item {
                    Group {
                        Setting.all
                            .filter { it.category == category }
                            .forEach { setting ->
                                val raw = settings.raw(setting.source)
                                ListItem(
                                    headlineContent = { Text(setting.title) },
                                    supportingContent = { Text(setting.display(raw, settings)) },
                                    trailingContent = {
                                        if (setting.input is Input.Color && raw != null)
                                            Box(
                                                Modifier.size(24.dp)
                                                    .clip(CircleShape)
                                                    .background(hexColor(raw))
                                            )
                                    },
                                    modifier =
                                        Modifier.fillMaxWidth()
                                            .clickable(
                                                enabled =
                                                    raw != null &&
                                                        s.connected &&
                                                        !s.settingsBusy &&
                                                        !s.commandBusy
                                            ) {
                                                onEdit(setting)
                                            }
                                            .testTag("setting_${setting.id}"),
                                    colors =
                                        ListItemDefaults.colors(
                                            containerColor =
                                                MaterialTheme.colorScheme.surfaceContainer
                                        ),
                                )
                            }
                    }
                }
            }
        }
    }
}

@Composable
fun EmptyState(text: String, icon: Int) {
    Column(
        Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Symbol(icon, null, Modifier.size(56.dp))
        Spacer(Modifier.height(16.dp))
        Text(text, style = MaterialTheme.typography.titleLarge)
    }
}

@Composable
fun SettingEditor(
    setting: Setting,
    settings: InverterSettings,
    s: AppState,
    apply: (String) -> Unit,
) {
    val current = settings.raw(setting.source)!!
    var draft by
        rememberSaveable(setting.id) {
            mutableStateOf(
                setting
                    .choices(settings)
                    ?.firstOrNull { it.value.toDoubleOrNull() == current.toDoubleOrNull() }
                    ?.value ?: current
            )
        }
    var review by remember { mutableStateOf(false) }
    val validation = runCatching { setting.command(draft, settings) }.exceptionOrNull()?.message
    val canReview =
        s.connected &&
            !s.settingsBusy &&
            !s.commandBusy &&
            validation == null &&
            !setting.verifies(draft, settings)
    Column(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.weight(1f),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (setting.input is Input.Color) item { Group { ColorControls(draft) { draft = it } } }
            else {
                val choices = setting.choices(settings)
                if (choices != null)
                    item {
                        Group {
                            choices.forEach { choice ->
                                ListItem(
                                    headlineContent = { Text(choice.label) },
                                    leadingContent = {
                                        RadioButton(draft == choice.value, { draft = choice.value })
                                    },
                                    modifier = Modifier.clickable { draft = choice.value },
                                    colors =
                                        ListItemDefaults.colors(
                                            containerColor =
                                                MaterialTheme.colorScheme.surfaceContainer
                                        ),
                                )
                            }
                        }
                    }
                else
                    item {
                        Group {
                            Column(Modifier.padding(16.dp)) {
                                OutlinedTextField(
                                    draft,
                                    { draft = it },
                                    label = { Text("New value") },
                                    suffix = { setting.unit?.let { Text(it) } },
                                    singleLine = true,
                                    keyboardOptions =
                                        KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                    modifier = Modifier.fillMaxWidth().testTag("settingValueInput"),
                                )
                                setting.hint?.let {
                                    Text(
                                        it,
                                        style = MaterialTheme.typography.bodySmall,
                                        modifier = Modifier.padding(top = 8.dp),
                                    )
                                }
                                val input = setting.input
                                if (input is Input.Integer)
                                    Row(
                                        Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.End,
                                    ) {
                                        OutlinedButton(
                                            onClick = {
                                                draft =
                                                    maxOf(
                                                            input.min,
                                                            (draft.toIntOrNull() ?: input.min) -
                                                                input.step,
                                                        )
                                                        .toString()
                                            }
                                        ) {
                                            Text("−")
                                        }
                                        Spacer(Modifier.width(12.dp))
                                        OutlinedButton(
                                            onClick = {
                                                draft =
                                                    minOf(
                                                            input.max,
                                                            (draft.toIntOrNull() ?: input.min) +
                                                                input.step,
                                                        )
                                                        .toString()
                                            }
                                        ) {
                                            Text("+")
                                        }
                                    }
                            }
                        }
                    }
            }
            if (setting.batteryCritical)
                item {
                    Text(
                        "Use your battery manufacturer’s values.",
                        color = MaterialTheme.colorScheme.tertiary,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            validation?.let { message ->
                item {
                    Text(
                        message,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
        FilledTonalButton(
            onClick = { review = true },
            enabled = canReview,
            modifier = Modifier.fillMaxWidth().padding(16.dp).testTag("reviewSettingChange"),
        ) {
            Text("Review")
        }
    }
    if (review)
        ModalBottomSheet(onDismissRequest = { review = false }) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Review change",
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.weight(1f),
                    )
                    Action(R.drawable.ic_close, "Cancel") { review = false }
                }
                Text(setting.title, style = MaterialTheme.typography.titleMedium)
                Group {
                    ListItem(
                        headlineContent = { Text("Current") },
                        trailingContent = { Text(setting.display(current, settings)) },
                    )
                    ListItem(
                        headlineContent = { Text("New") },
                        trailingContent = { Text(setting.display(draft, settings)) },
                    )
                }
                Text(
                    if (setting.batteryCritical)
                        "Use your battery manufacturer’s values. This takes effect immediately."
                    else "This takes effect immediately.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Button(
                    onClick = {
                        review = false
                        apply(draft)
                    },
                    enabled = canReview && !s.preview,
                    modifier = Modifier.fillMaxWidth().testTag("applySettingChange"),
                ) {
                    Text(if (s.preview) "Preview · writes disabled" else "Apply change")
                }
            }
        }
}

@Composable
fun Diagnostics(s: AppState, model: AppModel) {
    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                ReadCommand.entries.forEach { command ->
                    OutlinedButton(
                        onClick = { model.query(command) },
                        enabled = s.connected && !s.commandBusy && !s.settingsBusy,
                    ) {
                        Text(command.label)
                    }
                }
            }
        }
        if (s.commandBusy) item { LoadingIndicator() }
        if (s.commandResult.isNotEmpty()) {
            item { Text(s.commandTitle, style = MaterialTheme.typography.titleMedium) }
            item {
                androidx.compose.foundation.text.selection.SelectionContainer {
                    Text(
                        s.commandResult,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier =
                            Modifier.fillMaxWidth()
                                .background(
                                    MaterialTheme.colorScheme.surfaceContainer,
                                    RoundedCornerShape(24.dp),
                                )
                                .padding(16.dp),
                    )
                }
            }
        }
        if (!s.connected) item { Text("Inverter offline") }
    }
}
