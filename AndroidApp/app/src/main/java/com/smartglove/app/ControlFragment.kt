package com.smartglove.app

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.smartglove.app.databinding.FragmentControlBinding

class ControlFragment : Fragment(), GloveDataListener {

    private var _binding: FragmentControlBinding? = null
    private val binding get() = _binding!!

    private lateinit var mainActivity: MainActivity

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentControlBinding.inflate(inflater, container, false)
        mainActivity = activity as MainActivity

        setupClickListeners()
        return binding.root
    }

    override fun onResume() {
        super.onResume()
        mainActivity.addGloveDataListener(this)
        updateAllUI()
    }

    override fun onPause() {
        super.onPause()
        mainActivity.removeGloveDataListener(this)
    }

    private fun setupClickListeners() {
        binding.btnConnect.setOnClickListener {
            if (mainActivity.isConnected) {
                mainActivity.disconnect()
            } else {
                mainActivity.checkPermissionsAndScan()
            }
        }

        binding.btnStartCalibration.setOnClickListener {
            if(mainActivity.isConnected) {
                mainActivity.calibrationStep = 1
                updateCalibrationUI()
            }
        }

        binding.btnSetMin.setOnClickListener {
            mainActivity.minValue = mainActivity.currentRawValue
            mainActivity.sendCalibrationValue(true)
            if(mainActivity.calibrationStep == 1) mainActivity.calibrationStep = 2
            updateCalibrationUI()
            Toast.makeText(requireContext(), "Min: ${mainActivity.minValue}", Toast.LENGTH_SHORT).show()
        }

        binding.btnSetMax.setOnClickListener {
            mainActivity.maxValue = mainActivity.currentRawValue
            mainActivity.sendCalibrationValue(false)
            if(mainActivity.calibrationStep == 2) mainActivity.calibrationStep = 3

            if (!mainActivity.isCalibrated) {
                mainActivity.isCalibrated = true
                Toast.makeText(requireContext(), mainActivity.getString("calibration_done"), Toast.LENGTH_SHORT).show()
            }
            updateCalibrationUI()
        }
    }

    private fun updateAllUI() {
        updateLanguage()
        updateConnectionStatus(mainActivity.isConnected)
        updateCalibrationUI()
        updateRawValueText()
        if (!mainActivity.isConnected) {
            onStretchValueReceived(0)
        }
    }

    private fun updateLanguage() {
        binding.tvStretchLabel.text = mainActivity.getString("stretch")
        binding.btnStartCalibration.text = mainActivity.getString("start_calibration")
        binding.btnSetMin.text = mainActivity.getString("set_min")
        binding.btnSetMax.text = mainActivity.getString("set_max")
        binding.tvDeviceIdLabel.text = mainActivity.getString("device_id")
        binding.tvCalibrationLabel.text = mainActivity.getString("calibration")
    }

    private fun updateCalibrationUI() {
        binding.cardCalibration.visibility = if (mainActivity.isConnected) View.VISIBLE else View.GONE

        val calibStarted = mainActivity.calibrationStep > 0
        binding.btnStartCalibration.visibility = if (calibStarted) View.GONE else View.VISIBLE
        binding.btnSetMin.visibility = if (calibStarted) View.VISIBLE else View.GONE
        binding.btnSetMax.visibility = if (calibStarted) View.VISIBLE else View.GONE

        binding.btnSetMin.isEnabled = calibStarted
        binding.btnSetMax.isEnabled = calibStarted

        binding.tvCalibrationStatus.text = when (mainActivity.calibrationStep) {
            0 -> mainActivity.getString("put_on_glove")
            1 -> mainActivity.getString("calibration_step1")
            2 -> mainActivity.getString("calibration_step2")
            else -> mainActivity.getString("calibration_done")
        }

        binding.tvCalibrationValues.text = "Min: ${mainActivity.minValue} | Max: ${mainActivity.maxValue}"

        val statusText = if (mainActivity.isCalibrated) mainActivity.getString("calibrated") else mainActivity.getString("not_calibrated")
        binding.tvCalibratedStatus.text = statusText
        binding.tvCalibratedStatus.setTextColor(if (mainActivity.isCalibrated) 0xFF22c55e.toInt() else 0xFFef4444.toInt())
    }

    private fun updateRawValueText() {
        binding.tvRawValue.text = "${mainActivity.getString("raw_value")}: ${mainActivity.currentRawValue}"
    }

    override fun onConnectionStateUpdate(isConnected: Boolean) {
        updateConnectionStatus(isConnected)
        updateCalibrationUI()
    }

    override fun onStatusUpdate(status: String) {
        binding.tvStatus.text = status
    }

    override fun onDeviceIdRead(deviceId: String) {
        binding.tvDeviceId.text = deviceId
    }

    override fun onRawValueReceived(value: Int) {
        if(view != null) {
            updateRawValueText()
        }
    }

    override fun onStretchValueReceived(value: Int) {
        if(view != null) {
            binding.progressStretch.progress = value
            binding.tvStretchValue.text = "$value%"
        }
    }

    override fun onLanguageChanged() {
        updateLanguage()
        updateCalibrationUI()
    }

    private fun updateConnectionStatus(connected: Boolean) {
        binding.tvStatus.text = if (connected) mainActivity.getString("connected") else mainActivity.getString("not_connected")
        binding.btnConnect.text = if (connected) mainActivity.getString("disconnect") else mainActivity.getString("connect")
        binding.statusIndicator.setBackgroundResource(if (connected) R.drawable.indicator_connected else R.drawable.indicator_disconnected)
        binding.cardDeviceId.visibility = if (connected) View.VISIBLE else View.GONE
        if (!connected) {
            binding.tvDeviceId.text = "-"
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
