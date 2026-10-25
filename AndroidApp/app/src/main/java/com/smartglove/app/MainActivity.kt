package com.smartglove.app

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.tabs.TabLayoutMediator
import com.smartglove.app.databinding.ActivityMainBinding
import java.util.*

// Schnittstelle für Fragments, die BLE-Daten und Statusänderungen empfangen
interface GloveDataListener {
    fun onConnectionStateUpdate(isConnected: Boolean)
    fun onStatusUpdate(status: String)
    fun onDeviceIdRead(deviceId: String)
    fun onRawValueReceived(value: Int)
    fun onStretchValueReceived(value: Int)
    fun onLanguageChanged()
}

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    lateinit var prefs: SharedPreferences
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bluetoothGatt: BluetoothGatt? = null
    private var scanning = false
    private val handler = Handler(Looper.getMainLooper())

    // Kalibrierung
    var minValue = 0
    var maxValue = 1023
    var currentRawValue = 0
    var isCalibrated = false
    var calibrationStep = 0  // 0=nicht gestartet, 1=Min, 2=Max, 3=fertig
    var deviceId = ""

    // Sprache
    var isGerman = true

    // BLE Characteristics
    private var minCalCharacteristic: BluetoothGattCharacteristic? = null
    private var maxCalCharacteristic: BluetoothGattCharacteristic? = null

    // Gerät, auf dessen Kopplung gewartet wird
    private var pendingBondDevice: BluetoothDevice? = null

    // Registrierte Fragments
    private val dataListeners = mutableListOf<GloveDataListener>()

    val isConnected: Boolean
        get() = bluetoothGatt != null

    companion object {
        private const val TAG = "SmartGlove"
        private const val SCAN_PERIOD: Long = 10000
        const val PREFS_NAME = "SmartGlovePrefs"
        const val PREF_LANGUAGE = "language"
        const val PREF_HIGHSCORE = "highscore"
        private const val PREF_TRUSTED_GLOVES = "trusted_gloves"

        val SERVICE_UUID: UUID = UUID.fromString("19B10000-E8F2-537E-4F6C-D104768A1214")
        val STRETCH_CHAR_UUID: UUID = UUID.fromString("19B10001-E8F2-537E-4F6C-D104768A1214")
        val RAW_CHAR_UUID: UUID = UUID.fromString("19B10002-E8F2-537E-4F6C-D104768A1214")
        val DEVICE_ID_CHAR_UUID: UUID = UUID.fromString("19B10003-E8F2-537E-4F6C-D104768A1214")
        val MIN_CAL_CHAR_UUID: UUID = UUID.fromString("19B10004-E8F2-537E-4F6C-D104768A1214")
        val MAX_CAL_CHAR_UUID: UUID = UUID.fromString("19B10005-E8F2-537E-4F6C-D104768A1214")
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }

    // Strings für beide Sprachen
    private val strings = mapOf(
        "control" to Pair("Steuerung", "Control"),
        "game" to Pair("Spiel", "Game"),
        "app_title" to Pair("🧤 SmartGlove", "🧤 SmartGlove"),
        "not_connected" to Pair("Nicht verbunden", "Not connected"),
        "connected" to Pair("Verbunden", "Connected"),
        "connect" to Pair("Verbinden", "Connect"),
        "disconnect" to Pair("Trennen", "Disconnect"),
        "searching" to Pair("Suche SmartGlove...", "Searching SmartGlove..."),
        "connecting" to Pair("Verbinde...", "Connecting..."),
        "pairing" to Pair("Kopple mit Handschuh...", "Pairing with glove..."),
        "pairing_failed" to Pair("Kopplung fehlgeschlagen – PIN prüfen und erneut verbinden",
                                  "Pairing failed – check the PIN and connect again"),
        "not_found" to Pair("Nicht gefunden", "Not found"),
        "scan_failed" to Pair("Scan fehlgeschlagen", "Scan failed"),
        "stretch" to Pair("Dehnung", "Stretch"),
        "raw_value" to Pair("Rohwert", "Raw value"),
        "device_id" to Pair("Geräte-ID", "Device ID"),
        "calibration" to Pair("Kalibrierung", "Calibration"),
        "set_min" to Pair("Min setzen", "Set Min"),
        "set_max" to Pair("Max setzen", "Set Max"),
        "start_calibration" to Pair("Kalibrierung starten", "Start Calibration"),
        "calibration_step1" to Pair("Schritt 1: Finger ganz beugen, dann 'Min setzen' drücken",
                                     "Step 1: Fully bend finger, then press 'Set Min'"),
        "calibration_step2" to Pair("Schritt 2: Finger ganz durchstrecken, dann 'Max setzen' drücken",
                                     "Step 2: Fully extend finger, then press 'Set Max'"),
        "calibration_done" to Pair("Kalibrierung abgeschlossen!", "Calibration complete!"),
        "calibration_ready" to Pair("Bereit zur Kalibrierung", "Ready to calibrate"),
        "not_calibrated" to Pair("Nicht kalibriert", "Not calibrated"),
        "calibrated" to Pair("Kalibriert", "Calibrated"),
        "language" to Pair("Sprache: DE", "Language: EN"),
        "bluetooth_required" to Pair("Bluetooth-Berechtigungen erforderlich", "Bluetooth permissions required"),
        "enable_bluetooth" to Pair("Bitte Bluetooth aktivieren", "Please enable Bluetooth"),
        "ble_not_available" to Pair("BLE Scanner nicht verfügbar", "BLE Scanner not available"),
        "put_on_glove" to Pair("Handschuh anziehen und Kalibrierung starten", "Put on glove and start calibration"),
        "start_game" to Pair("Spiel starten", "Start Game"),
        "stop_game" to Pair("Spiel beenden", "Stop Game"),
        "score" to Pair("Punkte", "Score"),
        "highscore" to Pair("Highscore", "Highscore"),
        "game_title" to Pair("Weltraum Abenteuer", "Space Adventure"),
        "game_description" to Pair("Sammle Sterne und weiche Kometen aus!", "Collect stars and avoid comets!")
    )

    fun getString(key: String): String {
        val pair = strings[key] ?: return key
        return if (isGerman) pair.first else pair.second
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.all { it.value }) {
            initBluetooth()
        } else {
            Toast.makeText(this, getString("bluetooth_required"), Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Ab Android 15 zeichnet die App hinter Status- und Navigationsleiste
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        isGerman = prefs.getString(PREF_LANGUAGE, "de") == "de"

        // EXPORTED ist nötig: die Meldung kommt vom Bluetooth-Prozess (eigene UID), nicht vom System.
        // Sicher, weil ACTION_BOND_STATE_CHANGED ein geschützter Broadcast ist, den andere Apps nicht senden dürfen.
        ContextCompat.registerReceiver(
            this,
            bondReceiver,
            IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED),
            ContextCompat.RECEIVER_EXPORTED
        )

        setupViewPager()
        setupLanguageToggle()
        updateLanguageUI()
    }

    // Der Kopplungsdialog pausiert die App; danach den Kopplungsstatus selbst prüfen
    @SuppressLint("MissingPermission")
    override fun onResume() {
        super.onResume()
        val pending = pendingBondDevice ?: return
        when (pending.bondState) {
            BluetoothDevice.BOND_BONDED -> {
                pendingBondDevice = null
                connectToDevice(pending)
            }
            BluetoothDevice.BOND_NONE -> {
                pendingBondDevice = null
                showStatus("pairing_failed")
            }
        }
    }

    // Letzte Statusmeldung, damit sie nach onResume der Fragments nicht verloren geht
    private var lastStatusKey: String? = null

    val lastStatus: String?
        get() = lastStatusKey?.let { getString(it) }

    private fun showStatus(key: String) {
        lastStatusKey = key
        dataListeners.forEach { it.onStatusUpdate(getString(key)) }
    }

    private fun setupViewPager() {
        binding.viewPager.adapter = ViewPagerAdapter(this)
        TabLayoutMediator(binding.tabLayout, binding.viewPager) { tab, position ->
            tab.text = when (position) {
                0 -> getString("control")
                1 -> getString("game")
                else -> null
            }
        }.attach()
    }

    private fun setupLanguageToggle() {
        binding.ivLanguage.setOnClickListener {
            isGerman = !isGerman
            prefs.edit().putString(PREF_LANGUAGE, if (isGerman) "de" else "en").apply()
            updateLanguageUI()
            dataListeners.forEach { it.onLanguageChanged() }
        }
    }

    private fun updateLanguageUI() {
        binding.ivLanguage.setImageResource(if (isGerman) R.drawable.ic_flag_de else R.drawable.ic_flag_en)
        binding.tvTitle.text = getString("app_title")
        binding.tabLayout.getTabAt(0)?.text = getString("control")
        binding.tabLayout.getTabAt(1)?.text = getString("game")
    }

    fun addGloveDataListener(listener: GloveDataListener) {
        if (!dataListeners.contains(listener)) {
            dataListeners.add(listener)
        }
    }

    fun removeGloveDataListener(listener: GloveDataListener) {
        dataListeners.remove(listener)
    }

    fun checkPermissionsAndScan() {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT
            )
        } else {
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
        }

        val allGranted = permissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }

        if (allGranted) {
            initBluetooth()
        } else {
            requestPermissionLauncher.launch(permissions)
        }
    }

    @SuppressLint("MissingPermission")
    private fun initBluetooth() {
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter

        if (bluetoothAdapter == null || !bluetoothAdapter!!.isEnabled) {
            Toast.makeText(this, getString("enable_bluetooth"), Toast.LENGTH_LONG).show()
            return
        }

        scanForDevice()
    }

    @SuppressLint("MissingPermission")
    private fun scanForDevice() {
        val scanner = bluetoothAdapter?.bluetoothLeScanner
        if (scanner == null) {
            Toast.makeText(this, getString("ble_not_available"), Toast.LENGTH_SHORT).show()
            return
        }

        if (scanning) return

        scanning = true
        showStatus("searching")

        handler.postDelayed({
            if (scanning) {
                scanning = false
                scanner.stopScan(scanCallback)
                showStatus("not_found")
            }
        }, SCAN_PERIOD)

        // Nur Geräte mit dem SmartGlove-Service
        val filters = listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE_UUID)).build())
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        scanner.startScan(filters, settings, scanCallback)
    }

    // Handschuhe, die schon einmal gekoppelt wurden und in Android noch gekoppelt sind
    @SuppressLint("MissingPermission")
    private fun trustedGloves(): Set<String> {
        val saved = prefs.getStringSet(PREF_TRUSTED_GLOVES, emptySet()) ?: emptySet()
        val bonded = bluetoothAdapter?.bondedDevices?.map { it.address }?.toSet() ?: emptySet()
        return saved intersect bonded
    }

    private fun trustGlove(device: BluetoothDevice) {
        val saved = prefs.getStringSet(PREF_TRUSTED_GLOVES, emptySet()) ?: emptySet()
        prefs.edit().putStringSet(PREF_TRUSTED_GLOVES, saved + device.address).apply()
    }

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device

            // Ist schon ein Handschuh gekoppelt, wird nur diesem vertraut (Schutz vor gefälschten Geräten)
            val trusted = trustedGloves()
            if (trusted.isNotEmpty() && device.address !in trusted) {
                Log.d(TAG, "Ignoring unknown glove: ${device.address}")
                return
            }

            Log.d(TAG, "SmartGlove found: ${device.address}")

            scanning = false
            bluetoothAdapter?.bluetoothLeScanner?.stopScan(this)

            handler.post { connectToDevice(device) }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "Scan failed: $errorCode")
            handler.post {
                showStatus("scan_failed")
            }
        }
    }

    // Der Handschuh sendet Daten nur über eine verschlüsselte Verbindung, daher erst koppeln
    @SuppressLint("MissingPermission")
    private fun connectToDevice(device: BluetoothDevice) {
        if (device.bondState == BluetoothDevice.BOND_BONDED) {
            trustGlove(device)
            showStatus("connecting")
            bluetoothGatt = device.connectGatt(this, false, gattCallback)
        } else {
            pendingBondDevice = device
            showStatus("pairing")
            if (!device.createBond()) {
                pendingBondDevice = null
                showStatus("pairing_failed")
            }
        }
    }

    private val bondReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val device = IntentCompat.getParcelableExtra(intent, BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
            val pending = pendingBondDevice
            if (device == null || pending == null || device.address != pending.address) return

            when (intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE)) {
                BluetoothDevice.BOND_BONDED -> {
                    pendingBondDevice = null
                    connectToDevice(device)
                }
                BluetoothDevice.BOND_NONE -> {
                    pendingBondDevice = null
                    showStatus("pairing_failed")
                }
            }
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    calibrationStep = 0
                    isCalibrated = false
                    handler.post {
                        lastStatusKey = null
                        dataListeners.forEach { it.onConnectionStateUpdate(true) }
                    }
                    gatt.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    // GATT-Client freigeben, sonst gehen Android nach einigen Verbindungen die Clients aus
                    handler.post {
                        gatt.close()
                        if (bluetoothGatt == gatt) {
                            bluetoothGatt = null
                            minCalCharacteristic = null
                            maxCalCharacteristic = null
                        }
                        lastStatusKey = null
                        dataListeners.forEach { it.onConnectionStateUpdate(false) }
                    }
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                val service = gatt.getService(SERVICE_UUID)
                if (service != null) {
                    // Speichere Kalibrierungs-Characteristics
                    minCalCharacteristic = service.getCharacteristic(MIN_CAL_CHAR_UUID)
                    maxCalCharacteristic = service.getCharacteristic(MAX_CAL_CHAR_UUID)

                    // Aktiviere Notifications für Raw Value
                    val rawChar = service.getCharacteristic(RAW_CHAR_UUID)
                    if (rawChar != null) {
                        gatt.setCharacteristicNotification(rawChar, true)
                        val descriptor = rawChar.getDescriptor(CCCD_UUID)
                        descriptor?.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        gatt.writeDescriptor(descriptor)
                    }
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            // BLE erlaubt nur eine Operation gleichzeitig: Raw -> Stretch -> Device ID
            val parentCharacteristicUUID = descriptor.characteristic.uuid
            if (parentCharacteristicUUID == RAW_CHAR_UUID) {
                val stretchChar = gatt.getService(SERVICE_UUID)?.getCharacteristic(STRETCH_CHAR_UUID)
                if (stretchChar != null) {
                    gatt.setCharacteristicNotification(stretchChar, true)
                    val stretchDescriptor = stretchChar.getDescriptor(CCCD_UUID)
                    stretchDescriptor?.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    gatt.writeDescriptor(stretchDescriptor)
                }
            } else if (parentCharacteristicUUID == STRETCH_CHAR_UUID) {
                val deviceIdChar = gatt.getService(SERVICE_UUID)?.getCharacteristic(DEVICE_ID_CHAR_UUID)
                deviceIdChar?.let { gatt.readCharacteristic(it) }
            }
        }

        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (characteristic.uuid == DEVICE_ID_CHAR_UUID) {
                deviceId = characteristic.getStringValue(0) ?: ""
                handler.post { dataListeners.forEach { it.onDeviceIdRead(deviceId) } }
            }
        }

        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            val bytes = characteristic.value
            when (characteristic.uuid) {
                STRETCH_CHAR_UUID -> {
                    val value = parseIntValue(bytes)
                    handler.post { dataListeners.forEach { it.onStretchValueReceived(value) } }
                }
                RAW_CHAR_UUID -> {
                    currentRawValue = parseIntValue(bytes)
                    handler.post { dataListeners.forEach { it.onRawValueReceived(currentRawValue) } }
                }
            }
        }
    }

    // Little-Endian Integer aus BLE-Bytes lesen
    private fun parseIntValue(bytes: ByteArray?): Int {
        if (bytes == null || bytes.isEmpty()) return 0
        return when (bytes.size) {
            1 -> bytes[0].toInt() and 0xFF
            2 -> ((bytes[1].toInt() and 0xFF) shl 8) or (bytes[0].toInt() and 0xFF)
            4 -> ((bytes[3].toInt() and 0xFF) shl 24) or
                 ((bytes[2].toInt() and 0xFF) shl 16) or
                 ((bytes[1].toInt() and 0xFF) shl 8) or
                 (bytes[0].toInt() and 0xFF)
            else -> bytes[0].toInt() and 0xFF
        }
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        bluetoothGatt?.disconnect()
    }

    @SuppressLint("MissingPermission")
    fun sendCalibrationValue(isMin: Boolean) {
        val characteristic = if (isMin) minCalCharacteristic else maxCalCharacteristic
        val value = if (isMin) minValue else maxValue

        if (characteristic == null) {
            Toast.makeText(this, "Error: BLE Characteristic not found", Toast.LENGTH_SHORT).show()
            return
        }

        // Als 32-bit Little-Endian senden
        val bytes = byteArrayOf(
            (value and 0xFF).toByte(),
            ((value shr 8) and 0xFF).toByte(),
            ((value shr 16) and 0xFF).toByte(),
            ((value shr 24) and 0xFF).toByte()
        )
        characteristic.value = bytes
        bluetoothGatt?.writeCharacteristic(characteristic)
    }

    @SuppressLint("MissingPermission")
    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(bondReceiver)
        bluetoothGatt?.close()
        bluetoothGatt = null
    }
}
