package com.horsenma.mytv1

import android.annotation.SuppressLint
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.fragment.app.DialogFragment
import com.bumptech.glide.Glide
import com.horsenma.yourtv.databinding.ModalBinding


class ModalFragment : DialogFragment() {

    private var _binding: ModalBinding? = null
    private val binding get() = _binding!!

    private val handler = Handler(Looper.myLooper()!!)
    private val delayHideAppreciateModal = 10000L

    override fun onStart() {
        super.onStart()
        dialog?.window?.apply {
            addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
            decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = ModalBinding.inflate(inflater, container, false)
        return binding.root
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val url = arguments?.getString(KEY_URL)
        if (!url.isNullOrEmpty()) {
            val size = Utils.dpToPx(200)
            val u = url.trim()
            val img = QrCodeUtil().createQRCodeBitmap(u, size, size)

            Glide.with(requireContext())
                .load(img)
                .into(binding.modalImage)
            binding.modalText.text = u
            binding.modalText.visibility = View.VISIBLE
        } else {
            Glide.with(requireContext())
                .load(arguments?.getInt(KEY_DRAWABLE_ID))
                .into(binding.modalImage)
            binding.modalText.visibility = View.GONE
        }

        // 添加触摸监听
        binding.modal.setOnTouchListener { _, _ ->
            (activity as? MainActivity)?.settingActive() // 重置 SettingFragment 计时器
            handler.removeCallbacks(hideAppreciateModal)
            handler.postDelayed(hideAppreciateModal, delayHideAppreciateModal) // 重置 ModalFragment 计时器
            false
        }

        // 添加按键监听
        view.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN) {
                (activity as? MainActivity)?.settingActive() // 重置 SettingFragment 计时器
                handler.removeCallbacks(hideAppreciateModal)
                handler.postDelayed(hideAppreciateModal, delayHideAppreciateModal) // 重置 ModalFragment 计时器
                if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_ESCAPE) {
                    dismiss()
                    return@setOnKeyListener true
                }
            }
            false
        }

        handler.postDelayed(hideAppreciateModal, delayHideAppreciateModal)
    }

    private val hideAppreciateModal = Runnable {
        if (!this.isHidden) {
            this.dismiss()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
        handler.removeCallbacksAndMessages(null)
    }

    companion object {
        const val KEY_DRAWABLE_ID = "drawable_id"
        const val KEY_URL = "url"
        const val TAG = "ModalFragment"
    }
}
