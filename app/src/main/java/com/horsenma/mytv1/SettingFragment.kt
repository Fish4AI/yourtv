package com.horsenma.mytv1

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.marginBottom
import androidx.core.view.marginEnd
import androidx.core.view.marginTop
import androidx.fragment.app.Fragment
import com.horsenma.mytv1.models.TVList
import com.horsenma.yourtv.R
import com.horsenma.mytv1.SimpleServer.Companion.PORT
import com.horsenma.mytv1.ModalFragment.Companion.KEY_URL
import com.horsenma.yourtv.YourTVApplication
import com.horsenma.yourtv.databinding.SettingMytv1Binding
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import androidx.appcompat.widget.SwitchCompat
import android.os.Handler
import android.os.Looper

@Suppress("DEPRECATION")
class SettingFragment : Fragment() {

    private var _binding: SettingMytv1Binding? = null
    private val binding get() = _binding!!

    private lateinit var uri: Uri
    private val handler = Handler(Looper.getMainLooper())

    private fun serverUrl(): String {
        val host = PortUtil.lan() ?: "127.0.0.1"
        return "http://$host:$PORT"
    }

    @SuppressLint("SetTextI18n")
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = SettingMytv1Binding.inflate(inflater, container, false)
        return binding.root
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val context = requireActivity()
        val mainActivity = (activity as MainActivity)
        val application = context.applicationContext as YourTVApplication

        // Initialize controls
        binding.versionName.text = "v${context.appVersionName}"
        binding.version.visibility = View.GONE

        // Setup switches
        setupSwitch(binding.switchChannelReversal, SP.channelReversal) { isChecked ->
            SP.channelReversal = isChecked
            mainActivity.settingActive()
        }
        setupSwitch(binding.switchChannelNum, SP.channelNum) { isChecked ->
            SP.channelNum = isChecked
            mainActivity.settingActive()
        }
        setupSwitch(binding.switchTime, SP.time) { isChecked ->
            SP.time = isChecked
            mainActivity.settingActive()
        }
        SP.bootStartup = false
        binding.switchBootStartup.visibility = View.GONE
        setupSwitch(binding.switchConfigAutoLoad, SP.configAutoLoad) { isChecked ->
            SP.configAutoLoad = isChecked
            mainActivity.settingActive()
        }
        setupSwitch(binding.switchCompactMenu, SP.compactMenu) { isChecked ->
            SP.compactMenu = isChecked
            mainActivity.updateMenuSize()
            mainActivity.settingActive()
        }
        setupSwitch(binding.switchDisplaySeconds, SP.displaySeconds) { isChecked ->
            SP.displaySeconds = isChecked
            mainActivity.settingActive()
        }
        setupSwitch(binding.switchEnableWebviewType, !com.horsenma.yourtv.SP.enableWebviewType) { isChecked ->
            com.horsenma.yourtv.SP.enableWebviewType = !isChecked
            mainActivity.handleWebviewTypeSwitch(isChecked)
            mainActivity.settingActive()
        }

        setupSwitch(binding.switchFullScreenMode, com.horsenma.yourtv.SP.fullScreenMode) { isChecked ->
            com.horsenma.yourtv.SP.fullScreenMode = isChecked
            mainActivity.updateFullScreenMode(isChecked)
            (context.applicationContext as YourTVApplication).toggleFullScreenMode(isChecked)
            mainActivity.settingActive()
        }

        // 璁剧疆 switchWebviewType 鐨勫垵濮嬬姸鎬?
        updateWebviewTypeSwitch()

        binding.switchDisplaySeconds.isChecked = SP.displaySeconds
        binding.switchDisplaySeconds.isFocusable = true
        binding.switchDisplaySeconds.isFocusableInTouchMode = true

        binding.remoteSettings.setOnClickListener {
            val imageModalFragment = ModalFragment()
            val args = Bundle()
            args.putString(KEY_URL, serverUrl())
            imageModalFragment.arguments = args

            imageModalFragment.show(requireFragmentManager(), ModalFragment.TAG)
            mainActivity.settingActive()
        }


        binding.confirmConfig.setOnClickListener {
            var url = SP.configUrl!!
            url = Utils.formatUrl(url)
            uri = Uri.parse(url)
            if (uri.scheme == "") {
                uri = uri.buildUpon().scheme("http").build()
            }
            Log.i(TAG, "Uri $uri")
            if (uri.isAbsolute) {
                Log.i(TAG, "Uri ok")
                if (uri.scheme == "file") {
                    requestReadPermissions()
                } else {
                    TVList.parseUri(uri)
                }
            }
            mainActivity.settingActive()
        }

        binding.setting.setOnClickListener {
            hideSelf()
            (activity as? MainActivity)?.settingActive()
        }
        0
        // 淇敼閫€鍑烘寜閽负X5绠＄悊
        binding.exit.setOnClickListener {
            val isX5Available = YourTVApplication.getInstance().isX5Available()
            Log.d(TAG, "exit button: isX5Available=$isX5Available, useX5WebView=${SP.useX5WebView}")
            if (!isX5Available) {
                // 寮哄埗鍏抽棴 X5 妯″紡锛屼笌 switchWebviewType 淇濇寔涓€鑷?
                SP.useX5WebView = false
                binding.switchWebviewType.isChecked = false
                try {
                    val intent = Intent(context, TbsDebugActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    Toast.makeText(context, getString(R.string.x5_not_installed), Toast.LENGTH_LONG).show()
                    startActivity(intent)
                    Log.d(TAG, "Started TbsDebugActivity")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to start TbsDebugActivity: ${e.message}", e)
                    Toast.makeText(context, R.string.x5_install_failed, Toast.LENGTH_SHORT).show()
                }
            } else {
                // X5 鍙敤锛岀‘淇濆紑鍏崇姸鎬佷笌 SP.useX5WebView 涓€鑷?
                binding.switchWebviewType.isChecked = SP.useX5WebView
                Toast.makeText(context, R.string.x5_already_initialized, Toast.LENGTH_SHORT).show()
            }
        }

        binding.clear.setOnClickListener { showClearDialog() }

        // UI styling (aligned with yourtv)
        val txtTextSize = application.px2PxFont(binding.versionName.textSize)
        binding.content.layoutParams.width = application.px2Px(binding.content.layoutParams.width)
        binding.content.setPadding(
            application.px2Px(binding.content.paddingLeft),
            application.px2Px(binding.content.paddingTop),
            application.px2Px(binding.content.paddingRight),
            application.px2Px(binding.content.paddingBottom)
        )
        binding.version.textSize = txtTextSize
        val layoutParamsVersion = binding.version.layoutParams as ViewGroup.MarginLayoutParams
        layoutParamsVersion.topMargin = application.px2Px(binding.version.marginTop)
        layoutParamsVersion.bottomMargin = application.px2Px(binding.version.marginBottom)
        binding.version.layoutParams = layoutParamsVersion
        binding.versionName.textSize = txtTextSize

        // 璁剧疆鎸夐挳鏍峰紡
        val btnWidth = application.px2Px(binding.confirmConfig.layoutParams.width)
        val btnLayoutParams = binding.remoteSettings.layoutParams as ViewGroup.MarginLayoutParams
        btnLayoutParams.marginEnd = application.px2Px(binding.remoteSettings.marginEnd)

        for (i in listOf(
            binding.remoteSettings,
            binding.confirmConfig,
            binding.clear,
            binding.exit,
        )) {
            i.layoutParams.width = btnWidth
            i.textSize = txtTextSize
            i.layoutParams = btnLayoutParams
            i.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) {
                    //i.background = ContextCompat.getColor(context, R.color.focus).toDrawable()
                    i.setTextColor(ContextCompat.getColor(context, R.color.white))
                } else {
                    i.setTextColor(ContextCompat.getColor(context, R.color.blur))
                }
            }
        }

        // 璁剧疆寮€鍏虫牱寮?
        val textSizeSwitch = application.px2PxFont(binding.switchChannelReversal.textSize)
        val layoutParamsSwitch = binding.switchChannelReversal.layoutParams as ViewGroup.MarginLayoutParams
        layoutParamsSwitch.topMargin = application.px2Px(binding.switchChannelReversal.marginTop)

        for (i in listOf(
            binding.switchEnableWebviewType,
            binding.switchChannelReversal,
            binding.switchChannelNum,
            binding.switchTime,
            binding.switchBootStartup,
            binding.switchConfigAutoLoad,
            binding.switchCompactMenu,
            binding.switchDisplaySeconds,
            binding.switchWebviewType,
            binding.switchFullScreenMode,
        )) {
            i.textSize = textSizeSwitch
            i.layoutParams = layoutParamsSwitch
            i.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) {
                    i.setTextColor(ContextCompat.getColor(context, R.color.focus))
                } else {
                    i.setTextColor(ContextCompat.getColor(context, R.color.title_blur))
                }
            }
        }


        // Focus management
        view.isFocusable = true
        view.isFocusableInTouchMode = true

        // 纭繚 name 鍙仛鐒﹀苟娣诲姞鐒︾偣鐩戝惉
        binding.name.isFocusable = true
        binding.name.isFocusableInTouchMode = true
        binding.name.setOnFocusChangeListener { _, hasFocus ->
            binding.name.setTextColor(ContextCompat.getColor(context, if (hasFocus) R.color.focus else R.color.title_blur))
            Log.d(TAG, "name focus changed: hasFocus=$hasFocus")
        }

        view.post {
            binding.remoteSettings.isFocusable = true
            binding.remoteSettings.isFocusableInTouchMode = true
            binding.remoteSettings.requestFocus()
            binding.remoteSettings.onFocusChangeListener?.onFocusChange(binding.remoteSettings, true)
            Log.d(TAG, "Focus requested on remoteSettings")
        }

        // 娣诲姞瑙︽懜鐩戝惉
        binding.root.setOnTouchListener { _, _ ->
            (activity as? MainActivity)?.settingActive()
            false
        }

        // Key listener
        view.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN) {
                (activity as? MainActivity)?.settingActive()
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                        when (view.findFocus()?.id) {
                            R.id.switchEnableWebviewType -> binding.switchEnableWebviewType.toggle()
                            R.id.remote_settings -> binding.remoteSettings.performClick()
                            R.id.confirm_config -> binding.confirmConfig.performClick()
                            R.id.clear -> binding.clear.performClick()
                            R.id.exit -> binding.exit.performClick()
                            R.id.setting -> binding.setting.performClick()
                            R.id.switch_channel_reversal -> binding.switchChannelReversal.toggle()
                            R.id.switch_channel_num -> binding.switchChannelNum.toggle()
                            R.id.switch_time -> binding.switchTime.toggle()
                            R.id.switch_boot_startup -> binding.switchBootStartup.toggle()
                            R.id.switch_config_auto_load -> binding.switchConfigAutoLoad.toggle()
                            R.id.switch_compact_menu -> binding.switchCompactMenu.toggle()
                            R.id.switch_display_seconds -> binding.switchDisplaySeconds.toggle()
                            R.id.switch_webview_type -> binding.switchWebviewType.toggle()
                            R.id.switch_full_screen_mode -> binding.switchFullScreenMode.toggle()
                        }
                        return@setOnKeyListener true
                    }
                    KeyEvent.KEYCODE_BACK -> {
                        binding.setting.performClick()
                        return@setOnKeyListener true
                    }
                }
            }
            false
        }

        mainActivity.ready(TAG)
    }

    private fun updateWebviewTypeSwitch() {
        val isX5Available = YourTVApplication.getInstance().isX5Available()
        Log.d(TAG, "updateWebviewTypeSwitch: isX5Available=$isX5Available, useX5WebView=${SP.useX5WebView}")

        if (!isX5Available) {
            binding.switchWebviewType.visibility = View.GONE
            binding.switchWebviewType.isFocusable = false
            binding.switchWebviewType.isFocusableInTouchMode = false
            SP.useX5WebView = false
        } else {
            binding.switchWebviewType.visibility = View.VISIBLE
            binding.switchWebviewType.isChecked = SP.useX5WebView
            binding.switchWebviewType.isFocusable = true
            binding.switchWebviewType.isFocusableInTouchMode = true
            setupSwitch(binding.switchWebviewType, SP.useX5WebView) { isChecked ->
                Log.d(TAG, "switchWebviewType toggled: isChecked=$isChecked")
                SP.useX5WebView = isChecked
                if (SP.useX5WebView != isChecked) {
                    Log.e(TAG, "SP.useX5WebView save failed: expected $isChecked, got ${SP.useX5WebView}")
                    Toast.makeText(requireContext(), getString(R.string.save_settings_failed), Toast.LENGTH_SHORT).show()
                    binding.switchWebviewType.isChecked = !isChecked
                    return@setupSwitch
                }

                Toast.makeText(requireContext(), getString(R.string.webview_switched), Toast.LENGTH_LONG).show()
                // 娓呯悊 WebView 缂撳瓨
                if (isChecked) {
                    com.tencent.smtt.sdk.WebStorage.getInstance().deleteAllData()
                    com.tencent.smtt.sdk.CookieManager.getInstance().removeAllCookies(null)
                } else {
                    android.webkit.WebStorage.getInstance().deleteAllData()
                    android.webkit.CookieManager.getInstance().removeAllCookies(null)
                }

                // 寤惰繜閲嶅惎
                handler.postDelayed({
                    val restartIntent = Intent(requireContext(), com.horsenma.mytv1.MainActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                    }
                    requireContext().startActivity(restartIntent)
                    requireActivity().finish()
                }, 500L)
                (activity as? MainActivity)?.settingActive()
            }
        }
    }

    private fun hideSelf() {
        requireActivity().supportFragmentManager.beginTransaction()
            .hide(this)
            .commitAllowingStateLoss()
        (activity as MainActivity).addTimeFragment()
    }

    private fun setupSwitch(switch: SwitchCompat, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
        switch.isChecked = checked
        switch.setOnCheckedChangeListener { _, isChecked -> onCheckedChange(isChecked) }
        switch.isFocusable = true
        switch.isFocusableInTouchMode = true
    }

    private fun showClearDialog() {
        val dialog = androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setMessage(getString(R.string.confirm_reset))
            .setPositiveButton(R.string.confirm) { _, _ ->
                SP.configUrl = ""
                SP.channel = 0
                requireContext().deleteFile(TVList.CACHE_FILE_NAME)
                SP.deleteLike()
                SP.position = 0
                TVList.setPosition(0)
                TVList.setDisplaySeconds(SP.DEFAULT_DISPLAY_SECONDS)
                (activity as? MainActivity)?.settingActive()
                Log.d(TAG, "Settings cleared")
            }
            .setNegativeButton(R.string.cancel) { dialog, _ ->
                dialog.dismiss()
                (activity as? MainActivity)?.settingActive()
                Log.d(TAG, "Clear settings cancelled")
            }
            .setCancelable(true)
            .create()

        dialog.setOnShowListener {
            dialog.window?.setBackgroundDrawable(ColorDrawable(Color.parseColor("#33000000")))
            val displayMetrics = resources.displayMetrics
            dialog.window?.setLayout((displayMetrics.widthPixels * 0.30).toInt(), (displayMetrics.heightPixels * 0.25).toInt())

            val positiveButton = dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)
            val negativeButton = dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEGATIVE)

            positiveButton?.setOnFocusChangeListener { _, hasFocus ->
                positiveButton.setTextColor(ContextCompat.getColor(requireContext(), if (hasFocus) R.color.focus else R.color.blur))
                negativeButton?.setTextColor(ContextCompat.getColor(requireContext(), if (hasFocus) R.color.blur else R.color.focus))
            }
            negativeButton?.setOnFocusChangeListener { _, hasFocus ->
                negativeButton.setTextColor(ContextCompat.getColor(requireContext(), if (hasFocus) R.color.focus else R.color.blur))
                positiveButton?.setTextColor(ContextCompat.getColor(requireContext(), if (hasFocus) R.color.blur else R.color.focus))
            }

            positiveButton?.setTextColor(ContextCompat.getColor(requireContext(), R.color.focus))
            positiveButton?.requestFocus()
            negativeButton?.setTextColor(ContextCompat.getColor(requireContext(), R.color.blur))
        }
        dialog.show()
        (activity as? MainActivity)?.settingActive()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden) {
            // 鍒濆鍖?switchWebviewType
            updateWebviewTypeSwitch()

            view?.post {
                binding.remoteSettings.isFocusable = true
                binding.remoteSettings.isFocusableInTouchMode = true
                binding.remoteSettings.requestFocus()
                binding.remoteSettings.onFocusChangeListener?.onFocusChange(binding.remoteSettings, true)
                Log.d(TAG, "onHiddenChanged: Focus requested on remoteSettings")
            }
        }
    }

    private fun checkAndAddPermission(context: Context, permission: String, permissionsList: MutableList<String>) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && ContextCompat.checkSelfPermission(context, permission) != PackageManager.PERMISSION_GRANTED) {
            permissionsList.add(permission)
        }
    }


    private fun requestReadPermissions() {
        val context = requireContext()
        val permissionsList = mutableListOf<String>()
        checkAndAddPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE, permissionsList)
        if (permissionsList.isNotEmpty()) {
            ActivityCompat.requestPermissions(requireActivity(), permissionsList.toTypedArray(), PERMISSION_READ_EXTERNAL_STORAGE_REQUEST_CODE)
        } else {
            TVList.parseUri(uri)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_READ_EXTERNAL_STORAGE_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                TVList.parseUri(uri)
            } else {
                R.string.permission_failed.showToast(Toast.LENGTH_LONG)
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun String.showToast(duration: Int = Toast.LENGTH_SHORT) {
        Toast.makeText(requireContext(), this, duration).show()
    }

    private fun Int.showToast() {
        Toast.makeText(requireContext(), this, Toast.LENGTH_SHORT).show()
    }

    companion object {
        const val TAG = "SettingFragment"
        const val PERMISSION_READ_EXTERNAL_STORAGE_REQUEST_CODE = 2
    }
}


