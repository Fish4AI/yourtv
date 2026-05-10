package com.horsenma.yourtv


import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.core.view.marginBottom
import androidx.core.view.marginEnd
import androidx.core.view.marginTop
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import com.horsenma.yourtv.ModalFragment.Companion.KEY_URL
import com.horsenma.yourtv.SimpleServer.Companion.PORT
import com.horsenma.yourtv.databinding.SettingBinding
import kotlin.math.max
import kotlin.math.min
import android.view.KeyEvent
import android.widget.Toast
import android.os.Handler
import android.os.Looper
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import androidx.annotation.RequiresApi


@Suppress("DEPRECATION")
class SettingFragment : Fragment() {

    private var _binding: SettingBinding? = null
    private val binding get() = _binding!!

    private lateinit var viewModel: MainViewModel
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
        _binding = SettingBinding.inflate(inflater, container, false)
        return binding.root
    }

    // 鏂板锛氬鐢ㄨЕ鎽稿睆妫€娴嬮€昏緫
    private fun isTouchScreenDevice(context: Context): Boolean {
        val packageManager = context.packageManager
        val uiModeManager = context.getSystemService(Context.UI_MODE_SERVICE) as? android.app.UiModeManager
        val isTv = uiModeManager?.currentModeType == android.content.res.Configuration.UI_MODE_TYPE_TELEVISION
        val hasTouchScreen = packageManager.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)
        return hasTouchScreen && !isTv
    }

    @RequiresApi(Build.VERSION_CODES.P)
    @SuppressLint("SetTextI18n", "ClickableViewAccessibility")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val context = requireActivity()
        val mainActivity = (activity as MainActivity)
        val application = context.applicationContext as YourTVApplication
        val imageHelper = application.imageHelper
        viewModel = ViewModelProvider(context)[MainViewModel::class.java]
        binding.switchDisplaySeconds.setOnCheckedChangeListener { _, isChecked ->
            viewModel.setDisplaySeconds(isChecked)
        }

        // 鍒濆鍖栨帶浠讹紙淇濇寔鍘熸湁閫昏緫锛?
        binding.versionName.text = "v${context.appVersionName}"
        binding.version.visibility = View.GONE

        val switchChannelReversal = _binding?.switchChannelReversal
        switchChannelReversal?.isChecked = SP.channelReversal
        switchChannelReversal?.setOnCheckedChangeListener { _, isChecked ->
            SP.channelReversal = isChecked
            mainActivity.settingActive()
        }

        val switchChannelNum = _binding?.switchChannelNum
        switchChannelNum?.isChecked = SP.channelNum
        switchChannelNum?.setOnCheckedChangeListener { _, isChecked ->
            SP.channelNum = isChecked
            mainActivity.settingActive()
        }

        val switchTime = _binding?.switchTime
        switchTime?.isChecked = SP.time
        switchTime?.setOnCheckedChangeListener { _, isChecked ->
            SP.time = isChecked
            mainActivity.settingActive()
        }

        val switchBootStartup = _binding?.switchBootStartup
        SP.bootStartup = false
        switchBootStartup?.visibility = View.GONE

        val switchRepeatInfo = _binding?.switchRepeatInfo
        switchRepeatInfo?.isChecked = SP.repeatInfo
        switchRepeatInfo?.setOnCheckedChangeListener { _, isChecked ->
            SP.repeatInfo = isChecked
            mainActivity.settingActive()
        }

        val switchDefaultLike = _binding?.switchDefaultLike
        switchDefaultLike?.isChecked = SP.defaultLike
        switchDefaultLike?.setOnCheckedChangeListener { _, isChecked ->
            SP.defaultLike = isChecked
            mainActivity.settingActive()
        }

        val switchShowAllChannels = _binding?.switchShowAllChannels
        switchShowAllChannels?.isChecked = SP.showAllChannels

        val switchCompactMenu = _binding?.switchCompactMenu
        switchCompactMenu?.isChecked = SP.compactMenu
        switchCompactMenu?.setOnCheckedChangeListener { _, isChecked ->
            SP.compactMenu = isChecked
            mainActivity.updateMenuSize()
            mainActivity.settingActive()
        }

        val switchDisplaySeconds = _binding?.switchDisplaySeconds
        switchDisplaySeconds?.isChecked = SP.displaySeconds

        val switchSoftDecode = _binding?.switchSoftDecode
        switchSoftDecode?.isChecked = SP.softDecode
        switchSoftDecode?.setOnCheckedChangeListener { _, isChecked ->
            SP.softDecode = isChecked
            mainActivity.switchSoftDecode()
            mainActivity.settingActive()
        }

        val switchAutoSwitchSource = _binding?.switchAutoSwitchSource
        switchAutoSwitchSource?.isChecked = SP.autoSwitchSource
        switchAutoSwitchSource?.setOnCheckedChangeListener { _, isChecked ->
            SP.autoSwitchSource = isChecked
            mainActivity.settingActive()
        }

        val isTouchScreen = isTouchScreenDevice(context)
        val switchEnableScreenOffAudio = _binding?.switchEnableScreenOffAudio
        switchEnableScreenOffAudio?.isChecked = SP.enableScreenOffAudio
        switchEnableScreenOffAudio?.visibility = if (isTouchScreen) View.VISIBLE else View.GONE
        switchEnableScreenOffAudio?.setOnCheckedChangeListener { _, isChecked ->
            SP.enableScreenOffAudio = isChecked
            mainActivity.settingActive()
        }

        val switchFullScreenMode = _binding?.switchFullScreenMode
        switchFullScreenMode?.isChecked = SP.fullScreenMode
        switchFullScreenMode?.setOnCheckedChangeListener { _, isChecked ->
            SP.fullScreenMode = isChecked
            mainActivity.updateFullScreenMode(isChecked)
            (context.applicationContext as YourTVApplication).toggleFullScreenMode(isChecked) // 鏂板
            mainActivity.settingActive()
        }

        val switchEnableWebviewType = _binding?.switchEnableWebviewType
        switchEnableWebviewType?.isChecked = SP.enableWebviewType
        switchEnableWebviewType?.visibility =  View.VISIBLE
        switchEnableWebviewType?.setOnCheckedChangeListener { _, isChecked ->
            SP.enableWebviewType = isChecked
            (activity as MainActivity).handleWebviewTypeSwitch(isChecked)
            mainActivity.settingActive()
        }

        val switchShowSourceButton = _binding?.switchShowSourceButton
        switchShowSourceButton?.isChecked = SP.showSourceButton
        // 浠呭湪瑙︽懜灞忚澶囦笂鏄剧ず寮€鍏?
        switchShowSourceButton?.visibility = if (isTouchScreen) View.VISIBLE else View.GONE
        switchShowSourceButton?.setOnCheckedChangeListener { _, isChecked ->
            SP.showSourceButton = isChecked
            mainActivity.settingActive()
            // 閫氱煡 PlayerFragment 鏇存柊 btn_source 鍙鎬?
            if (mainActivity.playerFragment.isAdded) {
                mainActivity.playerFragment.setSourceButtonVisibility(true)
            }
        }

        val switchExit = _binding?.switchExit
        switchExit?.visibility = View.VISIBLE
        switchExit?.setOnClickListener {
            requireActivity().finishAffinity()
        }

        binding.remoteSettings.setOnClickListener {
            val imageModalFragment = ModalFragment()
            val args = Bundle()
            args.putString(KEY_URL, serverUrl())
            imageModalFragment.arguments = args

            imageModalFragment.show(requireFragmentManager(), ModalFragment.TAG)
            mainActivity.settingActive()
        }

        binding.confirmConfig.setOnClickListener {
            val sourcesFragment = SourcesFragment()
            sourcesFragment.show(requireFragmentManager(), SourcesFragment.TAG)
            mainActivity.settingActive()
        }

        binding.setting.setOnClickListener {
            hideSelf()
            (activity as? MainActivity)?.settingActive() // 鏂板
        }

        val txtTextSize = application.px2PxFont(binding.versionName.textSize)

        binding.content.layoutParams.width =
            application.px2Px(binding.content.layoutParams.width)
        binding.content.setPadding(
            application.px2Px(binding.content.paddingLeft),
            application.px2Px(binding.content.paddingTop),
            application.px2Px(binding.content.paddingRight),
            application.px2Px(binding.content.paddingBottom)
        )

        binding.name.textSize = application.px2PxFont(binding.name.textSize)
        binding.version.textSize = txtTextSize
        val layoutParamsVersion = binding.version.layoutParams as ViewGroup.MarginLayoutParams
        layoutParamsVersion.topMargin = application.px2Px(binding.version.marginTop)
        layoutParamsVersion.bottomMargin = application.px2Px(binding.version.marginBottom)
        binding.version.layoutParams = layoutParamsVersion

        val btnWidth = application.px2Px(binding.confirmConfig.layoutParams.width)

        val btnLayoutParams = binding.remoteSettings.layoutParams as ViewGroup.MarginLayoutParams
        btnLayoutParams.marginEnd = application.px2Px(binding.remoteSettings.marginEnd)

        binding.versionName.textSize = txtTextSize

        for (i in listOf(
            binding.remoteSettings,
            binding.confirmConfig,
            binding.clear,
        )) {
            i.layoutParams.width = btnWidth
            i.textSize = txtTextSize
            i.layoutParams = btnLayoutParams
            i.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) {
                    //i.background = ContextCompat.getColor(context,R.color.focus).toDrawable()
                    i.setTextColor(ContextCompat.getColor(context,R.color.white))
                } else {
                    i.setTextColor(ContextCompat.getColor(context,R.color.blur))
                }
            }
        }

        val textSizeSwitch = application.px2PxFont(binding.switchChannelReversal.textSize)

        val layoutParamsSwitch =
            binding.switchChannelReversal.layoutParams as ViewGroup.MarginLayoutParams
        layoutParamsSwitch.topMargin =
            application.px2Px(binding.switchChannelReversal.marginTop)

        for (i in listOf(
            binding.switchEnableWebviewType,
            binding.switchChannelReversal,
            binding.switchChannelNum,
            binding.switchTime,
            binding.switchBootStartup,
            binding.switchRepeatInfo,
            binding.switchDefaultLike,
            binding.switchShowAllChannels,
            binding.switchCompactMenu,
            binding.switchDisplaySeconds,
            binding.switchSoftDecode,
            binding.switchAutoSwitchSource,
            binding.switchShowSourceButton,
            binding.switchEnableScreenOffAudio,
            binding.switchFullScreenMode,
            binding.switchExit,
        )) {
            i.textSize = textSizeSwitch
            i.layoutParams = layoutParamsSwitch
            i.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) {
                    i.setTextColor(
                        ContextCompat.getColor(
                            context,
                            R.color.focus
                        )
                    )
                } else {
                    i.setTextColor(
                        ContextCompat.getColor(
                            context,
                            R.color.title_blur
                        )
                    )
                }
            }
        }

        binding.clear.setOnClickListener {
            // 椤ず纰鸿獚灏嶈┍妗?
            val dialog = androidx.appcompat.app.AlertDialog.Builder(requireContext())
                .setMessage(R.string.confirm_reset_settings)
                .setPositiveButton(R.string.confirm) { _, _ ->
                    Log.d(TAG, "Clearing non-playback settings")

                    // 閲嶇疆闈炴挱鏀剧浉闂滈厤缃?
                    SP.channelNum = SP.DEFAULT_CHANNEL_NUM
                    SP.channelReversal = SP.DEFAULT_CHANNEL_REVERSAL
                    SP.time = SP.DEFAULT_TIME
                    SP.bootStartup = SP.DEFAULT_BOOT_STARTUP
                    SP.repeatInfo = SP.DEFAULT_REPEAT_INFO
                    SP.defaultLike = false
                    SP.showAllChannels = SP.DEFAULT_SHOW_ALL_CHANNELS
                    SP.compactMenu = SP.DEFAULT_COMPACT_MENU
                    SP.displaySeconds = SP.DEFAULT_DISPLAY_SECONDS
                    SP.autoSwitchSource = SP.DEFAULT_AUTO_SWITCH_SOURCE
                    SP.showSourceButton = SP.DEFAULT_SHOW_SOURCE_BUTTON
                    SP.enableWebviewType = SP.DEFAULT_ENABLE_WEBVIEW_TYPE
                    SP.proxy = SP.DEFAULT_PROXY
                    SP.configUrl = SP.DEFAULT_CONFIG_URL
                    SP.fullScreenMode = SP.DEFAULT_FULL_SCREEN_MODE
                    SP.epg = SP.DEFAULT_EPG
                    SP.deleteLike()

                    // 鏇存柊 ViewModel 鍜?UI
                    viewModel.setDisplaySeconds(SP.DEFAULT_DISPLAY_SECONDS)
                    viewModel.updateEPG()
                    viewModel.groupModel.setChange() // 鏇存柊鑿滃柈椤ず
                    (activity as? MainActivity)?.updateMenuSize() // 鏇存柊鑿滃柈妯ｅ紡
                    (activity as? MainActivity)?.settingActive()

                    R.string.config_restored.showToast()
                    Log.d(TAG, "Non-playback settings reset completed")
                }
                .setNegativeButton(R.string.cancel) { dialog, _ ->
                    dialog.dismiss()
                    (activity as? MainActivity)?.settingActive() // 鏂板
                    Log.d(TAG, "Clear settings cancelled")
                }
                .setCancelable(true)
                .create()

            // 瑷疆灏嶈┍妗嗘ǎ寮忋€佸昂瀵稿拰鐒﹂粸
            dialog.setOnShowListener {
                // 瑷疆鑳屾櫙榛戣壊 80% 閫忔槑
                dialog.window?.setBackgroundDrawable(ColorDrawable(Color.parseColor("#33000000")))

                // 瑷疆灏哄锛堝搴?80% 灞忓箷锛岄珮搴?25% 灞忓箷锛?
                val displayMetrics = resources.displayMetrics
                val dialogWidth = (displayMetrics.widthPixels * 0.30).toInt()
                val dialogHeight = (displayMetrics.heightPixels * 0.25).toInt()
                dialog.window?.setLayout(dialogWidth, dialogHeight)

                // 鐛插彇鎸夐垥
                val positiveButton = dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)
                val negativeButton = dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEGATIVE)

                // 瑷疆鐒﹂粸璁婂寲鐩ｈ伣鍣?
                positiveButton?.setOnFocusChangeListener { _, hasFocus ->
                    positiveButton.setTextColor(ContextCompat.getColor(
                        requireContext(),
                        if (hasFocus) R.color.focus else R.color.blur
                    ))
                    negativeButton?.setTextColor(ContextCompat.getColor(
                        requireContext(),
                        if (hasFocus) R.color.blur else R.color.focus
                    ))
                    Log.d(TAG, "Positive button focus: $hasFocus")
                }
                negativeButton?.setOnFocusChangeListener { _, hasFocus ->
                    negativeButton.setTextColor(ContextCompat.getColor(
                        requireContext(),
                        if (hasFocus) R.color.focus else R.color.blur
                    ))
                    positiveButton?.setTextColor(ContextCompat.getColor(
                        requireContext(),
                        if (hasFocus) R.color.blur else R.color.focus
                    ))
                    Log.d(TAG, "Negative button focus: $hasFocus")
                }

                // 鍒濆鐒﹂粸鍜岄鑹?
                positiveButton?.let { button ->
                    button.setTextColor(ContextCompat.getColor(requireContext(), R.color.focus))
                    button.requestFocus()
                }
                negativeButton?.setTextColor(ContextCompat.getColor(requireContext(), R.color.blur))
                Log.d(TAG, "Positive button focused: ${positiveButton?.isFocused}")
            }
            dialog.show()
            (activity as? MainActivity)?.settingActive() // 鏂板
        }

        binding.switchShowAllChannels.setOnCheckedChangeListener { _, isChecked ->
            SP.showAllChannels = isChecked
            viewModel.groupModel.setChange()
            mainActivity.settingActive()
        }

        // 娣诲姞鎸夐敭浜嬩欢璋冭瘯
        binding.root.isFocusable = true
        binding.root.isFocusableInTouchMode = true

        // 缁熶竴鐒︾偣绠＄悊
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

        // 娣诲姞瑙︽懜鐩戝惉锛岄噸缃鏃跺櫒
        binding.root.setOnTouchListener { _, _ ->
            (activity as? MainActivity)?.settingActive()
            false
        }

        // 鏇存柊鎸夐敭鐩戝惉
        view.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN) {
                (activity as? MainActivity)?.settingActive() // 閲嶇疆璁℃椂鍣?
                Log.d(TAG, "Key pressed: $keyCode (Left: 21, Right: 22)")
            }
            false
        }
    }

    private fun confirmChannel() {
        SP.channel =
            min(max(SP.channel, 0), viewModel.groupModel.getAllList()!!.size())

        (activity as MainActivity).settingActive()
    }

    fun hideSelf() {
        requireActivity().supportFragmentManager.beginTransaction()
            .hide(this)
            .commitAllowingStateLoss()
        (activity as MainActivity).showTimeFragment()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden) {
            view?.post {
                binding.remoteSettings.isFocusable = true
                binding.remoteSettings.isFocusableInTouchMode = true
                binding.remoteSettings.requestFocus()
                binding.remoteSettings.onFocusChangeListener?.onFocusChange(binding.remoteSettings, true)
                Log.d(TAG, "onHiddenChanged: Focus requested on remoteSettings")
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "SettingFragment"
    }

    private fun Int.showToast() {
        Toast.makeText(requireContext(), this, Toast.LENGTH_SHORT).show()
    }
}
