package io.github.recloudstudio.sleepymore.ui

import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.recloudstudio.sleepymore.ConfigManager
import io.github.recloudstudio.sleepymore.MediaMethod
import io.github.recloudstudio.sleepymore.MiHomeCloudClient
import io.github.recloudstudio.sleepymore.MiHomeDevice
import io.github.recloudstudio.sleepymore.MiHomeMonitorService
import io.github.recloudstudio.sleepymore.MiHomeValueType
import io.github.recloudstudio.sleepymore.MiotSpecClient
import io.github.recloudstudio.sleepymore.MiotSpecProperty
import io.github.recloudstudio.sleepymore.R
import io.github.recloudstudio.sleepymore.RomDetector
import io.github.recloudstudio.sleepymore.SleepyConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Lightweight Material3 settings form (no Miuix preference animations). */
@Composable
fun ConfigScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // rememberSaveable keeps the user's in-progress edits across tab switches (the screen leaves
    // composition when the tab changes). mediaMethod is stored as its String name since the enum
    // isn't saveable; the `initialized` flag stops the async load from clobbering those edits.
    var serverUrl by rememberSaveable { mutableStateOf("") }
    var secret by rememberSaveable { mutableStateOf("") }
    var deviceId by rememberSaveable { mutableStateOf("") }
    var showName by rememberSaveable { mutableStateOf("") }
    var enabled by rememberSaveable { mutableStateOf(false) }
    var mediaEnabled by rememberSaveable { mutableStateOf(false) }
    var mediaDeviceId by rememberSaveable { mutableStateOf("") }
    var mediaShowName by rememberSaveable { mutableStateOf("") }
    var mediaMethodName by rememberSaveable { mutableStateOf(MediaMethod.AUTO.name) }
    var miHomeEnabled by rememberSaveable { mutableStateOf(false) }
    var miHomeUsername by rememberSaveable { mutableStateOf("") }
    var miHomePassword by rememberSaveable { mutableStateOf("") }
    var miHomeRegion by rememberSaveable { mutableStateOf("cn") }
    var miHomePollIntervalSec by rememberSaveable { mutableStateOf("120") }
    // Neither of these is rememberSaveable (would need a custom Saver) — they reload/reset
    // whenever this screen re-enters composition, same as everything gated by `initialized`.
    val miHomeItems = remember { mutableStateListOf<MiHomeItemState>() }
    // Devices fetched once from the account, shared by every source's device-picker so we don't
    // log in again per row. Cleared implicitly on screen re-entry — that's fine, it's just a cache.
    val miHomeFetchedDevices = remember { mutableStateListOf<MiHomeDevice>() }
    var miHomeFetchStatus by remember { mutableStateOf<String?>(null) }
    var miHomeFetchInProgress by remember { mutableStateOf(false) }
    // model -> its readable MIoT properties, fetched lazily per source's property-picker.
    val miHomePropertyCache = remember { mutableStateMapOf<String, List<MiotSpecProperty>>() }
    var statusMessage by rememberSaveable { mutableStateOf<String?>(null) }
    var initialized by rememberSaveable { mutableStateOf(false) }

    // Load the (disk / provider-backed) config off the main thread once, then seed the fields.
    LaunchedEffect(Unit) {
        if (initialized) return@LaunchedEffect
        val loaded =
            withContext(Dispatchers.IO) {
                runCatching { ConfigManager.loadConfig(context) }.getOrElse { SleepyConfig() }
            }
        serverUrl = loaded.serverUrl
        secret = loaded.secret
        deviceId = loaded.deviceId
        showName = loaded.showName
        enabled = loaded.enabled
        mediaEnabled = loaded.mediaEnabled
        mediaDeviceId = loaded.mediaDeviceId
        mediaShowName = loaded.mediaShowName
        mediaMethodName = loaded.mediaMethod.ifBlank { MediaMethod.AUTO.name }
        miHomeEnabled = loaded.miHomeEnabled
        miHomeUsername = loaded.miHomeUsername
        miHomePassword = loaded.miHomePassword
        miHomeRegion = loaded.miHomeRegion.ifBlank { "cn" }
        miHomePollIntervalSec = loaded.miHomePollIntervalSec.toString()
        miHomeItems.clear()
        miHomeItems.addAll(parseMiHomeItemsUI(loaded.miHomeDevicesJson))
        initialized = true
    }

    var recommendationText by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        recommendationText =
            withContext(Dispatchers.IO) {
                runCatching {
                        val recommendation = RomDetector.recommend(context)
                        val methodLabel =
                            when (recommendation.method) {
                                MediaMethod.SYSTEM_HOOK ->
                                    context.getString(R.string.media_method_system_hook)
                                MediaMethod.NOTIFICATION_LISTENER ->
                                    context.getString(R.string.media_method_notification_listener)
                                else -> context.getString(R.string.media_method_system_hook)
                            }
                        context.getString(
                            R.string.media_recommendation_format,
                            recommendation.androidVersion,
                            recommendation.rom.displayName,
                            methodLabel,
                            recommendation.reason
                        )
                    }
                    .getOrNull()
            }
    }

    val methodTitles =
        remember {
            MediaMethod.entries.associateWith { method ->
                when (method) {
                    MediaMethod.AUTO -> context.getString(R.string.media_method_auto)
                    MediaMethod.SYSTEM_HOOK ->
                        context.getString(R.string.media_method_system_hook)
                    MediaMethod.NOTIFICATION_LISTENER ->
                        context.getString(R.string.media_method_notification_listener)
                    MediaMethod.DUMPSYS_SHELL ->
                        context.getString(R.string.media_method_dumpsys_shell)
                }
            }
        }

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(top = 8.dp, bottom = 24.dp)
    ) {
        Spacer(Modifier.height(28.dp))
        Text(
            text = stringResource(R.string.settings_title),
            fontSize = 34.sp,
            fontWeight = FontWeight.Normal,
            color = MaterialTheme.colorScheme.onBackground
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.config_description),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium
        )
        Spacer(Modifier.height(20.dp))

        SectionTitle(stringResource(R.string.server_configuration))
        SettingsCard {
            SwitchRow(
                title = stringResource(R.string.enable_reporting),
                checked = enabled,
                onCheckedChange = { enabled = it }
            )
            Field(
                value = serverUrl,
                onValueChange = { serverUrl = it },
                label = stringResource(R.string.server_url_label),
                keyboardType = KeyboardType.Uri
            )
            Field(
                value = secret,
                onValueChange = { secret = it },
                label = stringResource(R.string.server_secret_label),
                isPassword = true
            )
            Field(
                value = deviceId,
                onValueChange = { deviceId = it },
                label = stringResource(R.string.device_id_label)
            )
            Field(
                value = showName,
                onValueChange = { showName = it },
                label = stringResource(R.string.display_name_label)
            )
        }

        Spacer(Modifier.height(16.dp))
        SectionTitle(stringResource(R.string.media_section_title))
        SettingsCard {
            Text(
                text = stringResource(R.string.media_section_description),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            SwitchRow(
                title = stringResource(R.string.media_enable_reporting),
                checked = mediaEnabled,
                onCheckedChange = { mediaEnabled = it }
            )
            Field(
                value = mediaDeviceId,
                onValueChange = { mediaDeviceId = it },
                label = stringResource(R.string.media_device_id_label)
            )
            Field(
                value = mediaShowName,
                onValueChange = { mediaShowName = it },
                label = stringResource(R.string.media_show_name_label)
            )
            Text(
                text = stringResource(R.string.media_method_label),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(top = 4.dp, bottom = 4.dp)
            )
            Column(modifier = Modifier.selectableGroup()) {
                MediaMethod.entries.forEach { method ->
                    val title = methodTitles[method].orEmpty()
                    val selected = mediaMethodName == method.name
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .selectable(
                                    selected = selected,
                                    onClick = { mediaMethodName = method.name },
                                    role = Role.RadioButton
                                )
                                .height(48.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = selected,
                            onClick = null
                        )
                        Text(
                            text = title,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(start = 4.dp)
                        )
                    }
                }
            }
            recommendationText?.let { recommendation ->
                Text(
                    text = recommendation,
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            }
            OutlinedButton(
                onClick = {
                    try {
                        context.startActivity(
                            Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                        )
                    } catch (e: Exception) {
                        Toast.makeText(
                                context,
                                context.getString(R.string.open_settings_failed, e.message),
                                Toast.LENGTH_SHORT
                            )
                            .show()
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.media_grant_notification_access))
            }
        }

        Spacer(Modifier.height(16.dp))
        SectionTitle(stringResource(R.string.mihome_section_title))
        SettingsCard {
            Text(
                text = stringResource(R.string.mihome_section_description),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            SwitchRow(
                title = "启用米家云上报",
                checked = miHomeEnabled,
                onCheckedChange = { miHomeEnabled = it }
            )
            Field(
                value = miHomeUsername,
                onValueChange = { miHomeUsername = it },
                label = "小米账号（手机号/邮箱）"
            )
            Field(
                value = miHomePassword,
                onValueChange = { miHomePassword = it },
                label = "小米账号密码",
                isPassword = true
            )
            Row(modifier = Modifier.fillMaxWidth()) {
                Field(
                    value = miHomeRegion,
                    onValueChange = { miHomeRegion = it },
                    label = "服务器区域 (cn/de/us/ru/sg/i2)",
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(10.dp))
                Field(
                    value = miHomePollIntervalSec,
                    onValueChange = { miHomePollIntervalSec = it.filter(Char::isDigit) },
                    label = "轮询间隔(秒)",
                    keyboardType = KeyboardType.Number,
                    modifier = Modifier.weight(1f)
                )
            }

            OutlinedButton(
                onClick = {
                    if (miHomeUsername.isBlank() || miHomePassword.isBlank()) {
                        Toast.makeText(context, "请先填写账号和密码", Toast.LENGTH_SHORT).show()
                        return@OutlinedButton
                    }
                    miHomeFetchInProgress = true
                    miHomeFetchStatus = "正在登录并获取设备列表…"
                    scope.launch {
                        val (devices, error) =
                            withContext(Dispatchers.IO) {
                                val cloud =
                                    MiHomeCloudClient(
                                        miHomeUsername.trim(),
                                        miHomePassword,
                                        miHomeRegion.trim().ifBlank { "cn" }
                                    )
                                val loggedIn = runCatching { cloud.login() }.getOrDefault(false)
                                if (!loggedIn) {
                                    null to (cloud.lastError ?: "未知错误")
                                } else {
                                    val devices = runCatching { cloud.fetchDeviceList() }.getOrNull()
                                    if (devices == null) devices to "登录成功，但获取设备列表失败（网络异常）"
                                    else devices to null
                                }
                            }
                        miHomeFetchInProgress = false
                        if (devices == null) {
                            miHomeFetchStatus = "获取失败：$error"
                        } else {
                            miHomeFetchedDevices.clear()
                            miHomeFetchedDevices.addAll(devices)
                            miHomeFetchStatus =
                                if (error != null) "获取失败：$error"
                                else "已获取 ${devices.size} 个设备，下面每条属性来源里可以直接选了"
                        }
                    }
                },
                enabled = !miHomeFetchInProgress,
                modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp)
            ) {
                Text(if (miHomeFetchInProgress) "获取中…" else "拉取米家设备列表")
            }
            if (miHomeFetchStatus != null) {
                Text(
                    text = miHomeFetchStatus!!,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }

            Spacer(Modifier.height(4.dp))
            HorizontalDivider()
            Spacer(Modifier.height(8.dp))
            Text(
                text = "上报项",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(bottom = 4.dp)
            )
            Text(
                text = "每个上报项对应 Sleepy 里的一台“设备”。先点上面的按钮拉设备列表，再在属性来源里选设备、选属性——不用自己去查 did/siid/piid。一个上报项可以加多条属性来源，用自定义文本模板拼在一起，比如“房间状态：温度{#1} 湿度{#2}”。",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            miHomeItems.forEachIndexed { index, item ->
                MiHomeItemEditor(
                    item = item,
                    fetchedDevices = miHomeFetchedDevices,
                    propertyCache = miHomePropertyCache,
                    scope = scope,
                    onDelete = { miHomeItems.removeAt(index) }
                )
            }

            OutlinedButton(
                onClick = {
                    miHomeItems.add(MiHomeItemState().apply { sources.add(MiHomeSourceState()) })
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("+ 添加上报项")
            }
        }

        Spacer(Modifier.height(20.dp))
        Button(
            onClick = {
                if (serverUrl.isBlank() ||
                    secret.isBlank() ||
                    deviceId.isBlank() ||
                    showName.isBlank()
                ) {
                    Toast.makeText(
                            context,
                            context.getString(R.string.fill_all_fields),
                            Toast.LENGTH_SHORT
                        )
                        .show()
                    return@Button
                }
                if (mediaEnabled && (mediaDeviceId.isBlank() || mediaShowName.isBlank())) {
                    Toast.makeText(
                            context,
                            context.getString(R.string.media_fill_required_fields),
                            Toast.LENGTH_SHORT
                        )
                        .show()
                    return@Button
                }
                if (miHomeEnabled && (miHomeUsername.isBlank() || miHomePassword.isBlank())) {
                    Toast.makeText(context, "请填写小米账号和密码", Toast.LENGTH_SHORT).show()
                    return@Button
                }
                val config =
                    SleepyConfig(
                        serverUrl = serverUrl.trim(),
                        secret = secret,
                        deviceId = deviceId.trim(),
                        showName = showName.trim(),
                        enabled = enabled,
                        mediaEnabled = mediaEnabled,
                        mediaDeviceId = mediaDeviceId.trim(),
                        mediaShowName = mediaShowName.trim(),
                        mediaMethod = mediaMethodName,
                        miHomeEnabled = miHomeEnabled,
                        miHomeUsername = miHomeUsername.trim(),
                        miHomePassword = miHomePassword,
                        miHomeRegion = miHomeRegion.trim().ifBlank { "cn" },
                        miHomePollIntervalSec = miHomePollIntervalSec.toIntOrNull()?.coerceAtLeast(30) ?: 120,
                        miHomeDevicesJson = serializeMiHomeItemsUI(miHomeItems)
                    )
                scope.launch {
                    val saved = withContext(Dispatchers.IO) { ConfigManager.saveConfig(context, config) }
                    if (saved) {
                        val serviceIntent = Intent(context, MiHomeMonitorService::class.java)
                        if (miHomeEnabled) {
                            context.startService(serviceIntent)
                        } else {
                            context.stopService(serviceIntent)
                        }
                        statusMessage =
                            context.getString(R.string.config_saved) +
                                "\n\n" +
                                ConfigManager.getConfigFilePath(context)
                        Toast.makeText(
                                context,
                                context.getString(R.string.config_saved_toast),
                                Toast.LENGTH_SHORT
                            )
                            .show()
                    } else {
                        statusMessage = null
                        Toast.makeText(context, context.getString(R.string.config_save_failed), Toast.LENGTH_SHORT)
                            .show()
                    }
                }
            },
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(52.dp)
        ) {
            Text(stringResource(R.string.save_configuration))
        }

        if (statusMessage != null) {
            Spacer(Modifier.height(12.dp))
            SettingsCard {
                Text(
                    text = statusMessage!!,
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }

        Spacer(Modifier.height(16.dp))
        SectionTitle(stringResource(R.string.instructions_title))
        SettingsCard {
            Text(
                text = stringResource(R.string.instructions),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(bottom = 8.dp)
    )
}

@Composable
private fun SettingsCard(content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors =
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp), content = { content() })
    }
}

@Composable
private fun Field(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    keyboardType: KeyboardType = KeyboardType.Text,
    isPassword: Boolean = false,
    modifier: Modifier = Modifier
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        visualTransformation =
            if (isPassword) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        modifier =
            modifier
                .fillMaxWidth()
                .padding(bottom = 10.dp)
    )
}

@Composable
private fun SwitchRow(title: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f)
        )
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/** Compose-observable editable single MIoT property source within a [MiHomeItemState]. */
private class MiHomeSourceState(
    did: String = "",
    model: String = "",
    valueType: MiHomeValueType = MiHomeValueType.NUMBER,
    siid: String = "",
    piid: String = "",
    trueText: String = "开启",
    falseText: String = "关闭",
    invertBoolean: Boolean = false,
    unit: String = "",
    decimals: String = "1",
    multiplier: String = "1",
    offset: String = "0"
) {
    var did by mutableStateOf(did)
    /** Device model string (e.g. "cgllc.airm.cgdn1"), captured from the device picker — needed to
     * look up its property list. Blank if `did` was typed by hand instead of picked. */
    var model by mutableStateOf(model)
    var valueType by mutableStateOf(valueType)
    var siid by mutableStateOf(siid)
    var piid by mutableStateOf(piid)
    var trueText by mutableStateOf(trueText)
    var falseText by mutableStateOf(falseText)
    var invertBoolean by mutableStateOf(invertBoolean)
    var unit by mutableStateOf(unit)
    var decimals by mutableStateOf(decimals)
    var multiplier by mutableStateOf(multiplier)
    var offset by mutableStateOf(offset)
    var showDevicePicker by mutableStateOf(false)
    var showPropertyPicker by mutableStateOf(false)
}

/** Compose-observable editable "report item" — one Sleepy device fed by 1..N property sources. */
private class MiHomeItemState(
    deviceId: String = "",
    showName: String = "",
    template: String = "{#1}"
) {
    var deviceId by mutableStateOf(deviceId)
    var showName by mutableStateOf(showName)
    var template by mutableStateOf(template)
    val sources = mutableStateListOf<MiHomeSourceState>()
}

private val MI_HOME_VALUE_TYPE_LABELS =
    mapOf(
        MiHomeValueType.BOOLEAN to "布尔（开关/存在/门窗等）",
        MiHomeValueType.NUMBER to "数值（温度/PM2.5/CO2等）",
        MiHomeValueType.STRING to "文本（原样显示）"
    )

/**
 * Optional quick-fill presets — purely a UI convenience that pre-populates the generic
 * [MiHomeSourceState] fields (unit/decimals/valueType/...). Adding a new sensor kind means adding
 * a preset here (or just typing values directly into the form); it never requires touching
 * [MiHomeMonitor]'s rendering logic.
 */
private data class MiHomePreset(
    val label: String,
    val valueType: MiHomeValueType,
    val unit: String = "",
    val decimals: String = "1",
    val trueText: String = "开启",
    val falseText: String = "关闭"
)

private val MI_HOME_PRESETS =
    listOf(
        MiHomePreset("温度", MiHomeValueType.NUMBER, unit = "°C", decimals = "1"),
        MiHomePreset("湿度", MiHomeValueType.NUMBER, unit = "%RH", decimals = "0"),
        MiHomePreset("PM2.5", MiHomeValueType.NUMBER, unit = "μg/m³", decimals = "0"),
        MiHomePreset("CO2", MiHomeValueType.NUMBER, unit = "ppm", decimals = "0"),
        MiHomePreset("光照度", MiHomeValueType.NUMBER, unit = "lux", decimals = "0"),
        MiHomePreset("电量", MiHomeValueType.NUMBER, unit = "%", decimals = "0"),
        MiHomePreset("人体存在", MiHomeValueType.BOOLEAN, trueText = "有人", falseText = "无人"),
        MiHomePreset("门窗", MiHomeValueType.BOOLEAN, trueText = "打开", falseText = "关闭"),
        MiHomePreset("开关/插座", MiHomeValueType.BOOLEAN, trueText = "开启", falseText = "关闭"),
        MiHomePreset("自定义", MiHomeValueType.STRING)
    )

@Composable
private fun MiHomeItemEditor(
    item: MiHomeItemState,
    fetchedDevices: List<MiHomeDevice>,
    propertyCache: MutableMap<String, List<MiotSpecProperty>>,
    scope: kotlinx.coroutines.CoroutineScope,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = item.showName.ifBlank { "新上报项" },
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = onDelete) { Text("删除上报项") }
            }

            Field(value = item.deviceId, onValueChange = { item.deviceId = it }, label = "Sleepy 设备 ID")
            Field(value = item.showName, onValueChange = { item.showName = it }, label = "Sleepy 显示名称")

            item.sources.forEachIndexed { index, src ->
                MiHomeSourceEditor(
                    source = src,
                    index = index,
                    fetchedDevices = fetchedDevices,
                    propertyCache = propertyCache,
                    scope = scope,
                    canDelete = item.sources.size > 1,
                    onDelete = { item.sources.removeAt(index) }
                )
            }

            OutlinedButton(
                onClick = {
                    item.sources.add(MiHomeSourceState())
                    // Give the template a sensible starting point once there's more than one
                    // source to combine — the user only needs to add wording around the
                    // placeholders, not learn the {#N} syntax from scratch.
                    if (item.sources.size > 1 && item.template == "{#1}") {
                        item.template = (1..item.sources.size).joinToString(" ") { "{#$it}" }
                    }
                },
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 8.dp)
            ) {
                Text("+ 添加属性来源")
            }

            if (item.sources.size > 1) {
                HorizontalDivider(modifier = Modifier.padding(bottom = 8.dp))
                Text(
                    text = "文本模板 — 可用占位符：" + (1..item.sources.size).joinToString(" ") { "{#$it}" },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
                Field(
                    value = item.template,
                    onValueChange = { item.template = it },
                    label = "例如：房间状态：温度{#1} 湿度{#2}"
                )
            }
        }
    }
}

@Composable
private fun MiHomeSourceEditor(
    source: MiHomeSourceState,
    index: Int,
    fetchedDevices: List<MiHomeDevice>,
    propertyCache: MutableMap<String, List<MiotSpecProperty>>,
    scope: kotlinx.coroutines.CoroutineScope,
    canDelete: Boolean,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "属性来源 #${index + 1}",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                if (canDelete) TextButton(onClick = onDelete) { Text("删除") }
            }

            Text(
                text = "常用预设（可选，仅用于快速填充，随时可改）",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(top = 4.dp, bottom = 4.dp)
            )
            Row(modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
                MI_HOME_PRESETS.forEach { preset ->
                    FilterChip(
                        selected = false,
                        onClick = {
                            source.valueType = preset.valueType
                            source.unit = preset.unit
                            source.decimals = preset.decimals
                            source.trueText = preset.trueText
                            source.falseText = preset.falseText
                        },
                        label = { Text(preset.label, style = MaterialTheme.typography.labelSmall) },
                        modifier = Modifier.padding(end = 6.dp)
                    )
                }
            }

            // --- Device picker: replaces manually typing `did` with tapping a fetched device. ---
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = if (source.did.isBlank()) "尚未选择设备" else "设备：${source.did}",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f)
                )
                TextButton(
                    onClick = { source.showDevicePicker = !source.showDevicePicker },
                    enabled = fetchedDevices.isNotEmpty()
                ) {
                    Text(if (fetchedDevices.isEmpty()) "先拉取设备列表" else "选设备")
                }
            }
            if (source.showDevicePicker && fetchedDevices.isNotEmpty()) {
                Row(modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
                    fetchedDevices.forEach { device ->
                        FilterChip(
                            selected = source.did == device.did,
                            onClick = {
                                source.did = device.did
                                source.model = device.model
                                source.showDevicePicker = false
                            },
                            label = {
                                Text(
                                    "${device.name.ifBlank { device.did }} (${device.model})",
                                    style = MaterialTheme.typography.labelSmall
                                )
                            },
                            modifier = Modifier.padding(end = 6.dp, bottom = 6.dp)
                        )
                    }
                }
            }
            Field(value = source.did, onValueChange = { source.did = it; source.model = "" }, label = "设备 did（也可手动填）")

            // --- Property picker: replaces manually typing siid/piid. Needs a known `model`,
            // which only the device picker above sets — hand-typed did's don't have one. ---
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text =
                        if (source.siid.isNotBlank() && source.piid.isNotBlank())
                            "属性：siid=${source.siid} piid=${source.piid}"
                        else "尚未选择属性（留空=仅上报在线/离线）",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f)
                )
                TextButton(
                    onClick = {
                        val model = source.model
                        if (model.isBlank()) return@TextButton
                        source.showPropertyPicker = !source.showPropertyPicker
                        if (source.showPropertyPicker && propertyCache[model] == null) {
                            scope.launch {
                                val props =
                                    withContext(Dispatchers.IO) {
                                        runCatching { MiotSpecClient.fetchProperties(model) }
                                            .getOrDefault(emptyList())
                                    }
                                propertyCache[model] = props
                            }
                        }
                    },
                    enabled = source.model.isNotBlank()
                ) {
                    Text(if (source.model.isBlank()) "需先选设备" else "选属性")
                }
            }
            if (source.showPropertyPicker && source.model.isNotBlank()) {
                val props = propertyCache[source.model]
                when {
                    props == null ->
                        Text(
                            "正在获取属性列表…",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(bottom = 4.dp)
                        )
                    props.isEmpty() ->
                        Text(
                            "该型号未在公开规格库中找到可读属性，请手动填 siid/piid。",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(bottom = 4.dp)
                        )
                    else ->
                        Row(modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
                            props.forEach { prop ->
                                FilterChip(
                                    selected = source.siid == prop.siid.toString() && source.piid == prop.piid.toString(),
                                    onClick = {
                                        source.siid = prop.siid.toString()
                                        source.piid = prop.piid.toString()
                                        source.valueType = prop.guessValueType()
                                        if (prop.unit.isNotBlank()) source.unit = prop.unit
                                        source.showPropertyPicker = false
                                    },
                                    label = {
                                        Text(
                                            "${prop.label} (${prop.siid}.${prop.piid})",
                                            style = MaterialTheme.typography.labelSmall
                                        )
                                    },
                                    modifier = Modifier.padding(end = 6.dp, bottom = 6.dp)
                                )
                            }
                        }
                }
            }
            Row(modifier = Modifier.fillMaxWidth()) {
                Field(
                    value = source.siid,
                    onValueChange = { source.siid = it.filter(Char::isDigit) },
                    label = "siid（也可手动填）",
                    keyboardType = KeyboardType.Number,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(10.dp))
                Field(
                    value = source.piid,
                    onValueChange = { source.piid = it.filter(Char::isDigit) },
                    label = "piid",
                    keyboardType = KeyboardType.Number,
                    modifier = Modifier.weight(1f)
                )
            }

            // Value type selector — the 3 generic kinds actually understood by MiHomeMonitor.
            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                MiHomeValueType.entries.forEach { t ->
                    FilterChip(
                        selected = source.valueType == t,
                        onClick = { source.valueType = t },
                        label = { Text(MI_HOME_VALUE_TYPE_LABELS[t] ?: t.name, style = MaterialTheme.typography.labelSmall) },
                        modifier = Modifier.padding(end = 6.dp)
                    )
                }
            }

            when (source.valueType) {
                MiHomeValueType.BOOLEAN -> {
                    Row(modifier = Modifier.fillMaxWidth()) {
                        Field(
                            value = source.trueText,
                            onValueChange = { source.trueText = it },
                            label = "为真时显示文本",
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(Modifier.width(10.dp))
                        Field(
                            value = source.falseText,
                            onValueChange = { source.falseText = it },
                            label = "为假时显示文本",
                            modifier = Modifier.weight(1f)
                        )
                    }
                    SwitchRow(
                        title = "反转真假值（部分属性语义相反，如“无人/关闭触发”）",
                        checked = source.invertBoolean,
                        onCheckedChange = { source.invertBoolean = it }
                    )
                }
                MiHomeValueType.NUMBER -> {
                    Row(modifier = Modifier.fillMaxWidth()) {
                        Field(
                            value = source.unit,
                            onValueChange = { source.unit = it },
                            label = "单位（如 °C / ppm / μg/m³）",
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(Modifier.width(10.dp))
                        Field(
                            value = source.decimals,
                            onValueChange = { source.decimals = it.filter(Char::isDigit) },
                            label = "小数位数",
                            keyboardType = KeyboardType.Number,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Text(
                        text = "高级：显示值 = 原始值 × 倍率 + 偏移（部分属性会整数放大上报，如湿度×10；一般留默认 1 / 0 即可）",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(top = 4.dp, bottom = 4.dp)
                    )
                    Row(modifier = Modifier.fillMaxWidth()) {
                        Field(
                            value = source.multiplier,
                            onValueChange = { source.multiplier = it },
                            label = "倍率",
                            keyboardType = KeyboardType.Decimal,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(Modifier.width(10.dp))
                        Field(
                            value = source.offset,
                            onValueChange = { source.offset = it },
                            label = "偏移",
                            keyboardType = KeyboardType.Decimal,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
                MiHomeValueType.STRING -> {
                    // No extra formatting fields — the raw property value is reported as-is.
                }
            }
        }
    }
}

private fun parseMiHomeItemsUI(json: String): List<MiHomeItemState> {
    return try {
        val arr = JSONArray(json)
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val item =
                MiHomeItemState(
                    deviceId = o.optString("deviceId"),
                    showName = o.optString("showName"),
                    template = o.optString("template", "{#1}")
                )
            val sourcesArr = o.optJSONArray("sources") ?: JSONArray()
            for (si in 0 until sourcesArr.length()) {
                val s = sourcesArr.optJSONObject(si) ?: continue
                val valueType =
                    try {
                        MiHomeValueType.valueOf(s.optString("valueType", "NUMBER").uppercase())
                    } catch (_: Exception) {
                        MiHomeValueType.NUMBER
                    }
                item.sources.add(
                    MiHomeSourceState(
                        did = s.optString("did"),
                        valueType = valueType,
                        siid = if (s.has("siid")) s.optInt("siid").toString() else "",
                        piid = if (s.has("piid")) s.optInt("piid").toString() else "",
                        trueText = s.optString("trueText", "开启"),
                        falseText = s.optString("falseText", "关闭"),
                        invertBoolean = s.optBoolean("invertBoolean", false),
                        unit = s.optString("unit", ""),
                        decimals = if (s.has("decimals")) s.optInt("decimals").toString() else "1",
                        multiplier = if (s.has("multiplier")) s.optDouble("multiplier").toString() else "1",
                        offset = if (s.has("offset")) s.optDouble("offset").toString() else "0"
                    )
                )
            }
            if (item.sources.isEmpty()) item.sources.add(MiHomeSourceState())
            item
        }
    } catch (_: Exception) {
        emptyList()
    }
}

private fun serializeMiHomeItemsUI(items: List<MiHomeItemState>): String {
    val arr = JSONArray()
    for (item in items) {
        if (item.deviceId.isBlank()) continue
        val validSources = item.sources.filter { it.did.isNotBlank() }
        if (validSources.isEmpty()) continue

        val o = JSONObject()
        o.put("deviceId", item.deviceId.trim())
        o.put("showName", item.showName.trim().ifBlank { item.deviceId.trim() })
        o.put("template", item.template.ifBlank { "{#1}" })

        val sourcesArr = JSONArray()
        for (src in validSources) {
            val s = JSONObject()
            s.put("did", src.did.trim())
            s.put("valueType", src.valueType.name)
            src.siid.toIntOrNull()?.let { s.put("siid", it) }
            src.piid.toIntOrNull()?.let { s.put("piid", it) }
            s.put("trueText", src.trueText)
            s.put("falseText", src.falseText)
            s.put("invertBoolean", src.invertBoolean)
            s.put("unit", src.unit)
            s.put("decimals", src.decimals.toIntOrNull() ?: 1)
            s.put("multiplier", src.multiplier.toDoubleOrNull() ?: 1.0)
            s.put("offset", src.offset.toDoubleOrNull() ?: 0.0)
            s.put("offlineText", "离线")
            sourcesArr.put(s)
        }
        o.put("sources", sourcesArr)
        arr.put(o)
    }
    return arr.toString()
}
