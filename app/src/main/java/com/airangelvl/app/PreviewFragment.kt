package com.airangelvl.app

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.graphics.SurfaceTexture
import android.os.Bundle
import android.view.LayoutInflater
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.airangelvl.app.databinding.FragmentPreviewBinding
import com.airangelvl.core.camera.PreviewTarget
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class PreviewFragment : Fragment() {
    private var binding: FragmentPreviewBinding? = null
    private val viewModel: MainViewModel by activityViewModels()
    private var recordAnimator: ObjectAnimator? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        FragmentPreviewBinding.inflate(inflater, container, false).also { binding = it }.root

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val texture = requireNotNull(binding).previewTexture
        texture.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                viewModel.onPreviewAvailable(PreviewTarget.Texture(surface, width, height))
            }
            override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
                viewModel.onPreviewAvailable(PreviewTarget.Texture(surface, width, height))
            }
            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                // The coordinator releases this texture after UVC/GL and any recording have stopped.
                viewModel.onPreviewReleased(surface)
                return false
            }
            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
        }
        if (texture.isAvailable) texture.surfaceTexture?.let {
            viewModel.onPreviewAvailable(PreviewTarget.Texture(it, texture.width, texture.height))
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.uiState.collect { updateRecordingIndicator(it.isRecording) } }
                launch { viewModel.captureEvents.collect { flashCapture() } }
            }
        }
    }

    private fun updateRecordingIndicator(recording: Boolean) {
        val indicator = binding?.recordIndicatorText ?: return
        if (recording) {
            indicator.visibility = View.VISIBLE
            if (recordAnimator == null) {
                recordAnimator = ObjectAnimator.ofFloat(indicator, View.ALPHA, 1f, 0.4f).apply {
                    duration = 800L
                    repeatMode = ValueAnimator.REVERSE
                    repeatCount = ValueAnimator.INFINITE
                    start()
                }
            }
        } else {
            recordAnimator?.cancel()
            recordAnimator = null
            indicator.visibility = View.GONE
        }
    }

    private fun flashCapture() {
        val flash = binding?.captureFlash ?: return
        flash.visibility = View.VISIBLE
        flash.alpha = 0.6f
        flash.animate().alpha(0f).setDuration(180L).withEndAction { flash.visibility = View.GONE }
    }

    override fun onDestroyView() {
        recordAnimator?.cancel()
        recordAnimator = null
        binding?.captureFlash?.animate()?.cancel()
        binding = null
        super.onDestroyView()
    }

    companion object { fun newInstance() = PreviewFragment() }
}
