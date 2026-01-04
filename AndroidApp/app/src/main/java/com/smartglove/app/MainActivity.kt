package com.smartglove.app

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.smartglove.app.databinding.ActivityMainBinding
import java.util.*

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: SharedPreferences
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bluetoothGatt: BluetoothGatt? = null
    private var scanning = false
    private val handler = Handler(Looper.getMainLooper())

    // Kalibrierung
    private var minValue = 0
    private var maxValue = 1023
    private var currentRawValue = 0
    private var isCalibrated = false
    private var calibrationStep = 0  // 0=nicht gestartet, 1=Min, 2=Max, 3=fertig
    private var deviceId = ""

    // Sprache
    private var isGerman = true

    // BLE Characteristics
    private var minCalCharacteristic: BluetoothGattCharacteristic? = null
    private var maxCalCharacteristic: BluetoothGattCharacteristic? = null

    companion object {
        private const val TAG = "SmartGlove"
        private const val SCAN_PERIOD: Long = 10000
        private const val DEVICE_NAME_PREFIX = "SmartGlove"
        private const val PREFS_NAME = "SmartGlovePrefs"
        private const val PREF_LANGUAGE = "language"
        
        val SERVICE_UUID: UUID = UUID.fromString("19B10000-E8F2-537E-4F6C-D104768A1214")
        val STRETCH_CHAR_UUID: UUID = UUID.fromString("19B10001-E8F2-537E-4F6C-D104768A1214")
        val RAW_CHAR_UUID: UUID = UUID.fromString("19B10002-E8F2-537E-4F6C-D104768A1214")
        val DEVICE_ID_CHAR_UUID: UUID = UUID.fromString("19B10003-E8F2-537E-4F6C-D104768A1214")
        val MIN_CAL_CHAR_UUID: UUID = UUID.fromString("19B10004-E8F2-537E-4F6C-D104768A1214")
        val MAX_CAL_CHAR_UUID: UUID = UUID.fromString("19B10005-E8F2-537E-4F6C-D104768A1214")
        val CALIBRATED_CHAR_UUID: UUID = UUID.fromString("19B10006-E8F2-537E-4F6C-D104768A1214")
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }

    // Strings für beide Sprachen
    private val strings = mapOf(
        "app_title" to Pair("🧤 SmartGlove", "🧤 SmartGlove"),
        "not_connected" to Pair("Nicht verbunden", "Not connected"),
        "connected" to Pair("Verbunden", "Connected"),
        "connect" to Pair("Verbinden", "Connect"),
        "disconnect" to Pair("Trennen", "Disconnect"),
        "searching" to Pair("Suche SmartGlove...", "Searching SmartGlove..."),
        "connecting" to Pair("Verbinde...", "Connecting..."),
        "not_found" to Pair("Nicht gefunden", "Not found"),
        "scan_failed" to Pair("Scan fehlgeschlagen", "Scan failed"),
        "stretch" to Pair("Dehnung", "Stretch"),
        "raw_value" to Pair("Rohwert", "Raw value"),
        "device_id" to Pair("Geräte-ID", "Device ID"),
        "calibration" to Pair("Kalibrierung", "Calibration"),
        "set_min" to Pair("Min setzen", "Set Min"),
        "set_max" to Pair("Max setzen", "Set Max"),
        "start_calibration" to Pair("Kalibrierung starten", "Start Calibration"),
        "calibration_step1" to Pair("Schritt 1: Finger ganz durchstrecken, dann 'Min setzen' drücken", 
                                     "Step 1: Fully extend finger, then press 'Set Min'"),
        "calibration_step2" to Pair("Schritt 2: Finger ganz beugen, dann 'Max setzen' drücken", 
                                     "Step 2: Fully bend finger, then press 'Set Max'"),
        "calibration_done" to Pair("Kalibrierung abgeschlossen!", "Calibration complete!"),
        "calibration_ready" to Pair("Bereit zur Kalibrierung", "Ready to calibrate"),
        "not_calibrated" to Pair("Nicht kalibriert", "Not calibrated"),
        "calibrated" to Pair("Kalibriert", "Calibrated"),
        "language" to Pair("Sprache: DE", "Language: EN"),
        "bluetooth_required" to Pair("Bluetooth-Berechtigungen erforderlich", "Bluetooth permissions required"),
        "enable_bluetooth" to Pair("Bitte Bluetooth aktivieren", "Please enable Bluetooth"),
        "ble_not_available" to Pair("BLE Scanner nicht verfügbar", "BLE Scanner not available"),
        "put_on_glove" to Pair("Handschuh anziehen und Kalibrierung starten", "Put on glove and start calibration")
    )

    private fun getString(key: String): String {
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
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        isGerman = prefs.getString(PREF_LANGUAGE, "de") == "de"

        setupClickListeners()
        updateLanguage()
        updateConnectionStatus(false)
        updateCalibrationUI()
    }

    private fun setupClickListeners() {
        binding.btnConnect.setOnClickListener {
            if (bluetoothGatt != null) {
                disconnect()
            } else {
                checkPermissionsAndScan()
            }
        }

        binding.btnLanguage.setOnClickListener {
            isGerman = !isGerman
            prefs.edit().putString(PREF_LANGUAGE, if (isGerman) "de" else "en").apply()
            updateLanguage()
        }

        binding.btnStartCalibration.setOnClickListener {
            if (bluetoothGatt != null) {
                calibrationStep = 1
                updateCalibrationUI()
            }
        }

        binding.btnSetMin.setOnClickListener {
            Log.d(TAG, "btnSetMin clicked, calibrationStep=$calibrationStep, currentRawValue=$currentRawValue")
            if (calibrationStep >= 1) {
                minValue = currentRawValue
                Log.d(TAG, "Setting minValue to $minValue")
                sendCalibrationValue(minCalCharacteristic, minValue)
                calibrationStep = 2
                updateCalibrationUI()
                Toast.makeText(this, "Min: $minValue", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnSetMax.setOnClickListener {
            Log.d(TAG, "btnSetMax clicked, calibrationStep=$calibrationStep, currentRawValue=$currentRawValue")
            if (calibrationStep >= 2) {
                maxValue = currentRawValue
                Log.d(TAG, "Setting maxValue to $maxValue")
                sendCalibrationValue(maxCalCharacteristic, maxValue)
                calibrationStep = 3
                isCalibrated = true
                updateCalibrationUI()
                Toast.makeText(this, getString("calibration_done") + " Max: $maxValue", Toast.LENGTH_SHORT).show()
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun sendCalibrationValue(characteristic: BluetoothGattCharacteristic?, value: Int) {
        if (characteristic == null) {
            Log.e(TAG, "Characteristic ist NULL! Wert kann nicht gesendet werden.")
            Toast.makeText(this, "Fehler: BLE Characteristic nicht gefunden", Toast.LENGTH_SHORT).show()
            return
        }
        characteristic.setValue(value, BluetoothGattCharacteristic.FORMAT_SINT32, 0)
        val success = bluetoothGatt?.writeCharacteristic(characteristic) ?: false
        Log.d(TAG, "Kalibrierungswert gesendet: $value, Erfolg: $success")
    }

    private fun updateLanguage() {
        binding.tvTitle.text = getString("app_title")
        binding.btnLanguage.text = getString("language")
        binding.tvStretchLabel.text = getString("stretch")
        binding.btnStartCalibration.text = getString("start_calibration")
        binding.btnSetMin.text = getString("set_min")
        binding.btnSetMax.text = getString("set_max")
        binding.tvDeviceIdLabel.text = getString("device_id")
        binding.tvCalibrationLabel.text = getString("calibration")
        updateConnectionStatus(bluetoothGatt != null)
        updateCalibrationUI()
        updateRawValueText()
    }

    private fun updateCalibrationUI() {
        val connected = bluetoothGatt != null
        
        binding.cardCalibration.visibility = if (connected) View.VISIBLE else View.GONE
        binding.btnStartCalibration.visibility = if (calibrationStep == 0) View.VISIBLE else View.GONE
        binding.btnSetMin.visibility = if (calibrationStep >= 1) View.VISIBLE else View.GONE
        binding.btnSetMax.visibility = if (calibrationStep >= 1) View.VISIBLE else View.GONE
        
        binding.btnSetMin.isEnabled = calibrationStep == 1
        binding.btnSetMax.isEnabled = calibrationStep == 2

        binding.tvCalibrationStatus.text = when (calibrationStep) {
            0 -> getString("put_on_glove")
            1 -> getString("calibration_step1")
            2 -> getString("calibration_step2")
            3 -> getString("calibration_done")
            else -> ""
        }

        binding.tvCalibrationValues.text = "Min: $minValue | Max: $maxValue"
        
        val statusText = if (isCalibrated) getString("calibrated") else getString("not_calibrated")
        binding.tvCalibratedStatus.text = statusText
        binding.tvCalibratedStatus.setTextColor(
            if (isCalibrated) 0xFF22c55e.toInt() else 0xFFef4444.toInt()
        )
    }

    private fun updateRawValueText() {
        binding.tvRawValue.text = "${getString("raw_value")}: $currentRawValue"
    }

    private fun checkPermissionsAndScan() {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.ACCESS_FINE_LOCATION
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

        binding.tvStatus.text = getString("searching")
        binding.btnConnect.isEnabled = false

        handler.postDelayed({
            if (scanning) {
                scanning = false
                scanner.stopScan(scanCallback)
                binding.tvStatus.text = getString("not_found")
                binding.btnConnect.isEnabled = true
            }
        }, SCAN_PERIOD)

        scanning = true
        scanner.startScan(scanCallback)
    }

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            val name = device.name ?: return
            
            if (name.startsWith(DEVICE_NAME_PREFIX)) {
                Log.d(TAG, "SmartGlove gefunden: ${device.address}")
                
                scanning = false
                bluetoothAdapter?.bluetoothLeScanner?.stopScan(this)
                
                handler.post {
                    binding.tvStatus.text = getString("connecting")
                    connectToDevice(device)
                }
            }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "Scan fehlgeschlagen: $errorCode")
            handler.post {
                binding.tvStatus.text = getString("scan_failed")
                binding.btnConnect.isEnabled = true
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun connectToDevice(device: BluetoothDevice) {
        bluetoothGatt = device.connectGatt(this, false, gattCallback)
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.d(TAG, "Verbunden")
                    handler.post { 
                        updateConnectionStatus(true)
                        calibrationStep = 0
                        isCalibrated = false
                        updateCalibrationUI()
                    }
                    gatt.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.d(TAG, "Getrennt")
                    handler.post { 
                        updateConnectionStatus(false)
                        calibrationStep = 0
                        updateCalibrationUI()
                    }
                    bluetoothGatt = null
                    minCalCharacteristic = null
                    maxCalCharacteristic = null
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

                    // Lese Device ID
                    val deviceIdChar = service.getCharacteristic(DEVICE_ID_CHAR_UUID)
                    deviceIdChar?.let { gatt.readCharacteristic(it) }

                    // Aktiviere Notifications für Raw Value
                    val rawChar = service.getCharacteristic(RAW_CHAR_UUID)
                    if (rawChar != null) {
                        gatt.setCharacteristicNotification(rawChar, true)
                        val descriptor = rawChar.getDescriptor(CCCD_UUID)
                        descriptor?.let {
                            it.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                            gatt.writeDescriptor(it)
                        }
                    }
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            // Nach Raw aktiviere auch Stretch
            if (descriptor.characteristic.uuid == RAW_CHAR_UUID) {
                val service = gatt.getService(SERVICE_UUID)
                val stretchChar = service?.getCharacteristic(STRETCH_CHAR_UUID)
                if (stretchChar != null) {
                    gatt.setCharacteristicNotification(stretchChar, true)
                    val stretchDescriptor = stretchChar.getDescriptor(CCCD_UUID)
                    stretchDescriptor?.let {
                        it.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        gatt.writeDescriptor(it)
                    }
                }
            }
        }

        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS && characteristic.uuid == DEVICE_ID_CHAR_UUID) {
                deviceId = characteristic.getStringValue(0) ?: ""
                handler.post {
                    binding.tvDeviceId.text = deviceId
                }
            }
        }

        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            when (characteristic.uuid) {
                STRETCH_CHAR_UUID -> {
                    val value = characteristic.getIntValue(BluetoothGattCharacteristic.FORMAT_SINT32, 0) ?: 0
                    handler.post {
                        binding.progressStretch.progress = value
                        binding.tvStretchValue.text = "$value%"
                    }
                }
                RAW_CHAR_UUID -> {
                    val value = characteristic.getIntValue(BluetoothGattCharacteristic.FORMAT_SINT32, 0) ?: 0
                    currentRawValue = value
                    handler.post {
                        updateRawValueText()
                        // Aktualisiere auch die Kalibrierungsanzeige mit aktuellem Wert
                        updateCalibrationUI()
                    }
                }
            }
        }
    }

    private fun updateConnectionStatus(connected: Boolean) {
        if (connected) {
            binding.tvStatus.text = getString("connected")
            binding.btnConnect.text = getString("disconnect")
            binding.statusIndicator.setBackgroundResource(R.drawable.indicator_connected)
            binding.cardDeviceId.visibility = View.VISIBLE
        } else {
            binding.tvStatus.text = getString("not_connected")
            binding.btnConnect.text = getString("connect")
            binding.statusIndicator.setBackgroundResource(R.drawable.indicator_disconnected)
            binding.cardDeviceId.visibility = View.GONE
            binding.tvDeviceId.text = "-"
            deviceId = ""
        }
        binding.btnConnect.isEnabled = true
    }

    @SuppressLint("MissingPermission")
    private fun disconnect() {
        bluetoothGatt?.disconnect()
        bluetoothGatt?.close()
        bluetoothGatt = null
        minCalCharacteristic = null
        maxCalCharacteristic = null
        calibrationStep = 0
        updateConnectionStatus(false)
        updateCalibrationUI()
    }

    override fun onDestroy() {
        super.onDestroy()
        disconnect()
    }
}
