package com.airangelvl.app

import android.Manifest
import android.content.pm.PackageManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.airangelvl.app.databinding.ActivityMainBinding
import com.airangelvl.app.camera.PreviewAspectMode
import com.airangelvl.app.camera.CameraPhase
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.slider.Slider
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    private val viewModel: MainViewModel by viewModels()
    private lateinit var binding: ActivityMainBinding
    private enum class SaveAction { Photo, Video }
    private var pendingSave: SaveAction? = null
    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        viewModel.onCameraPermissionChanged()
        if (!granted) viewModel.showMessage(getString(R.string.camera_permission_denied))
    }
    private val storagePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val action = pendingSave
        pendingSave = null
        if (granted && action != null && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) performSave(action)
        else if (!granted) viewModel.showMessage(getString(R.string.storage_permission_denied))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, windowInsets ->
            val bars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.updatePadding(left = bars.left, top = bars.top, right = bars.right, bottom = bars.bottom)
            windowInsets
        }
        configureWindowBrightness()
        if (savedInstanceState == null) {
            supportFragmentManager
                .beginTransaction()
                .replace(binding.previewContainer.id, PreviewFragment.newInstance())
                .commitNow()
        }
        subscribeToState()
        bindControls()
        val requestedBefore = getPreferences(MODE_PRIVATE).getBoolean("camera_permission_requested", false)
        if (savedInstanceState == null && !requestedBefore && !hasCameraPermission()) requestCameraPermission()
    }

    override fun onResume() {
        super.onResume()
        viewModel.onForegroundChanged(true)
    }

    override fun onStop() {
        viewModel.onForegroundChanged(false)
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        viewModel.retry()
    }

    private fun configureWindowBrightness() {
        val attributes = window.attributes
        attributes.screenBrightness = 1f
        window.attributes = attributes
    }

    private fun hasCameraPermission() = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun requestCameraPermission() {
        if (hasCameraPermission()) { viewModel.retry(); return }
        val previouslyRequested = getPreferences(MODE_PRIVATE).getBoolean("camera_permission_requested", false)
        if (previouslyRequested && !shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)) {
            MaterialAlertDialogBuilder(this).setTitle(R.string.camera_permission_title)
                .setMessage(R.string.camera_permission_settings)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.open_app_settings) { _, _ ->
                    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
                }.show()
        } else {
            getPreferences(MODE_PRIVATE).edit().putBoolean("camera_permission_requested", true).apply()
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun requestSave(action: SaveAction) {
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            pendingSave = action
            storagePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else performSave(action)
    }

    private fun performSave(action: SaveAction) {
        if (!viewModel.uiState.value.canCapture) return
        when (action) {
            SaveAction.Photo -> viewModel.onCaptureTapped()
            SaveAction.Video -> viewModel.onRecordTapped()
        }
    }

    private fun bindControls() {
        binding.settingsButton.setOnClickListener { showSettingsSheet() }
        binding.recordToggle.setOnClickListener {
            if (viewModel.uiState.value.phase in setOf(CameraPhase.Recording, CameraPhase.StartingRecording)) viewModel.onRecordTapped()
            else requestSave(SaveAction.Video)
        }
        binding.captureButton.setOnClickListener {
            requestSave(SaveAction.Photo)
        }
        binding.retryButton.setOnClickListener { if (hasCameraPermission()) viewModel.retry() else requestCameraPermission() }
    }

    private fun showSettingsSheet() {
        val dialog = BottomSheetDialog(this)
        val sheet = layoutInflater.inflate(R.layout.bottom_sheet_settings, android.widget.FrameLayout(this), false)
        dialog.setContentView(sheet)
        dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED

        val aspectGroup = sheet.findViewById<android.widget.RadioGroup>(R.id.aspect_group)
        aspectGroup.check(radioIdForAspect(viewModel.aspectMode.value))
        aspectGroup.setOnCheckedChangeListener { _, checkedId ->
            val selected = aspectForRadioId(checkedId) ?: return@setOnCheckedChangeListener
            if (viewModel.aspectMode.value != selected) {
                viewModel.onAspectModeChanged(selected)
            }
        }
        val settingsScope = sheet.findViewById<android.widget.TextView>(R.id.settings_image_scope)
        val framingJob = lifecycleScope.launch {
            viewModel.uiState.collect { state ->
                for (index in 0 until aspectGroup.childCount) {
                    aspectGroup.getChildAt(index).isEnabled = state.canChangeFraming
                }
                settingsScope.setText(if (state.canChangeFraming) R.string.settings_saved_image else R.string.settings_recording_framing)
            }
        }
        dialog.setOnDismissListener { framingJob.cancel() }

        val brightnessValue = sheet.findViewById<android.widget.TextView>(R.id.value_brightness)
        val contrastValue = sheet.findViewById<android.widget.TextView>(R.id.value_contrast)
        val saturationValue = sheet.findViewById<android.widget.TextView>(R.id.value_saturation)
        val brightnessSlider = sheet.findViewById<Slider>(R.id.slider_brightness)
        val contrastSlider = sheet.findViewById<Slider>(R.id.slider_contrast)
        val saturationSlider = sheet.findViewById<Slider>(R.id.slider_saturation)

        fun applyAdjustments(adjustments: ImageAdjustments) {
            brightnessValue.text = getString(R.string.value_brightness, adjustments.brightness)
            contrastValue.text = getString(R.string.value_percent, adjustments.contrast)
            saturationValue.text = getString(R.string.value_percent, adjustments.saturation)
            brightnessSlider.value = adjustments.brightness.toFloat()
            contrastSlider.value = adjustments.contrast.toFloat()
            saturationSlider.value = adjustments.saturation.toFloat()
        }

        applyAdjustments(viewModel.imageAdjustments.value)

        brightnessSlider.addOnChangeListener { _, value, fromUser ->
            if (!fromUser) return@addOnChangeListener
            val updated = viewModel.imageAdjustments.value.copy(brightness = value.roundToInt())
            viewModel.onImageAdjustmentsChanged(updated)
            brightnessValue.text = getString(R.string.value_brightness, updated.brightness)
        }
        contrastSlider.addOnChangeListener { _, value, fromUser ->
            if (!fromUser) return@addOnChangeListener
            val updated = viewModel.imageAdjustments.value.copy(contrast = value.roundToInt())
            viewModel.onImageAdjustmentsChanged(updated)
            contrastValue.text = getString(R.string.value_percent, updated.contrast)
        }
        saturationSlider.addOnChangeListener { _, value, fromUser ->
            if (!fromUser) return@addOnChangeListener
            val updated = viewModel.imageAdjustments.value.copy(saturation = value.roundToInt())
            viewModel.onImageAdjustmentsChanged(updated)
            saturationValue.text = getString(R.string.value_percent, updated.saturation)
        }

        sheet.findViewById<com.google.android.material.button.MaterialButton>(R.id.button_reset_image)
            .setOnClickListener {
                val defaults = ImageAdjustments()
                viewModel.onImageAdjustmentsChanged(defaults)
                applyAdjustments(defaults)
            }

        sheet.findViewById<View>(R.id.button_privacy_policy).setOnClickListener {
            val policy = assets.open("privacy-policy.txt").bufferedReader().use { it.readText() }
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.privacy_policy_title)
                .setMessage(policy)
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }
        sheet.findViewById<View>(R.id.button_about).setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.about_title)
                .setMessage(R.string.about_message)
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }
        dialog.show()
    }

    private fun radioIdForAspect(mode: PreviewAspectMode): Int {
        return when (mode) {
            PreviewAspectMode.FULL_SCREEN -> R.id.radio_aspect_fullscreen
            PreviewAspectMode.ASPECT_16_9 -> R.id.radio_aspect_16_9
            PreviewAspectMode.ASPECT_5_4 -> R.id.radio_aspect_5_4
            PreviewAspectMode.ASPECT_4_3 -> R.id.radio_aspect_4_3
        }
    }

    private fun aspectForRadioId(viewId: Int): PreviewAspectMode? {
        return when (viewId) {
            R.id.radio_aspect_fullscreen -> PreviewAspectMode.FULL_SCREEN
            R.id.radio_aspect_16_9 -> PreviewAspectMode.ASPECT_16_9
            R.id.radio_aspect_5_4 -> PreviewAspectMode.ASPECT_5_4
            R.id.radio_aspect_4_3 -> PreviewAspectMode.ASPECT_4_3
            else -> null
        }
    }

    private fun subscribeToState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                viewModel.uiState.collect { state ->
                    binding.recordToggle.text = when (state.phase) {
                        CameraPhase.Recording -> getString(R.string.action_stop)
                        CameraPhase.StartingRecording -> getString(R.string.action_cancel_record)
                        CameraPhase.StoppingRecording -> getString(R.string.action_saving)
                        else -> getString(R.string.action_record)
                    }
                    binding.recordToggle.isEnabled = state.canRecord
                    binding.captureButton.isEnabled = state.canCapture
                    binding.statusText.text = state.message
                    binding.retryButton.visibility = if (state.canRetry) View.VISIBLE else View.GONE
                }
                }
                launch {
                    viewModel.settingsReady.collect { ready ->
                        binding.settingsButton.isEnabled = ready
                    }
                }
            }
        }
    }
}
