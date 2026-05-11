package com.horsenma.yourtv

import android.content.Context
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.core.view.marginBottom
import androidx.core.view.marginStart
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.horsenma.yourtv.databinding.GroupItemBinding
import com.horsenma.yourtv.models.TVGroupModel
import com.horsenma.yourtv.models.TVListModel


class GroupAdapter(
    private val context: Context,
    private val recyclerView: RecyclerView,
    private var tvGroupModel: TVGroupModel,
) :
    RecyclerView.Adapter<GroupAdapter.ViewHolder>() {

    private var listener: ItemListener? = null
    private var focused: View? = null
    private var defaultFocused = false
    private var defaultFocus: Int = -1

    var visible = false
    private var localExpanded = false

    private var first = true

    val application = context.applicationContext as YourTVApplication

    private data class DisplayEntry(
        val model: TVListModel?,
        val title: String,
        val groupIndex: Int,
        val isLocalParent: Boolean = false,
        val isLocalChild: Boolean = false,
    )

    private fun displayEntries(): List<DisplayEntry> {
        val entries = mutableListOf<DisplayEntry>()
        var localParentAdded = false

        tvGroupModel.tvGroupValue.forEachIndexed { index, model ->
            if (!SP.showAllChannels && index == 1) {
                return@forEachIndexed
            }

            val groupName = model.getName()
            if (isLocalGroup(groupName, index)) {
                if (!localParentAdded) {
                    entries.add(
                        DisplayEntry(
                            model = null,
                            title = localParentTitle(),
                            groupIndex = LOCAL_PARENT_INDEX,
                            isLocalParent = true,
                        )
                    )
                    localParentAdded = true
                }
                if (localExpanded) {
                    entries.add(
                        DisplayEntry(
                            model = model,
                            title = groupName,
                            groupIndex = model.getGroupIndex(),
                            isLocalChild = true,
                        )
                    )
                }
            } else {
                entries.add(DisplayEntry(model, groupName, model.getGroupIndex()))
            }
        }

        return entries
    }

    private fun isLocalGroup(groupName: String, index: Int): Boolean {
        return index > 1 &&
                groupName != CCTV_GROUP_NAME &&
                groupName != SATELLITE_GROUP_NAME &&
                groupName != LOCAL_PARENT_NAME
    }

    private fun localParentTitle(): String {
        return if (localExpanded) "$LOCAL_PARENT_NAME v" else "$LOCAL_PARENT_NAME >"
    }

    private fun toggleLocalGroup(focusFirstChild: Boolean) {
        localExpanded = !localExpanded
        notifyDataSetChanged()
        if (focusFirstChild && localExpanded) {
            recyclerView.post {
                val firstLocalChild = displayEntries().indexOfFirst { it.isLocalChild }
                if (firstLocalChild >= 0) {
                    (recyclerView.layoutManager as? LinearLayoutManager)
                        ?.scrollToPositionWithOffset(firstLocalChild, 0)
                    recyclerView.findViewHolderForAdapterPosition(firstLocalChild)
                        ?.itemView
                        ?.requestFocus()
                }
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val inflater = LayoutInflater.from(context)
        val binding = GroupItemBinding.inflate(inflater, parent, false)

        val layoutParams = binding.title.layoutParams as ViewGroup.MarginLayoutParams
        layoutParams.marginStart = application.px2Px(binding.title.marginStart)
        layoutParams.bottomMargin = application.px2Px(binding.title.marginBottom)
        binding.title.layoutParams = layoutParams

        binding.title.textSize = application.px2PxFont(binding.title.textSize)

        binding.root.isFocusable = true
        binding.root.isFocusableInTouchMode = true
        return ViewHolder(context, binding)
    }

    fun focusable(able: Boolean) {
        recyclerView.isFocusable = able
        recyclerView.isFocusableInTouchMode = able
        if (able) {
            recyclerView.descendantFocusability = ViewGroup.FOCUS_BEFORE_DESCENDANTS
        } else {
            recyclerView.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        }
    }

    fun clear() {
        focused?.clearFocus()
        recyclerView.invalidate()
    }

    override fun onBindViewHolder(viewHolder: ViewHolder, position: Int) {
        val entry = displayEntries().getOrNull(position) ?: return
        val view = viewHolder.itemView

        if (!defaultFocused && position == defaultFocus) {
            view.requestFocus()
            defaultFocused = true
        }

        val onFocusChangeListener = View.OnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                entry.model?.let { listTVModel ->
                    listener?.onItemFocusChange(listTVModel, true)
                    val p = listTVModel.getGroupIndex()
                    if (p != tvGroupModel.positionValue) {
                        tvGroupModel.setPosition(p)
                    }
                }
                viewHolder.focus(true)
                focused = view

            } else {
                entry.model?.let { listener?.onItemFocusChange(it, false) }
                viewHolder.focus(false)
            }
        }

        view.onFocusChangeListener = onFocusChangeListener

        view.setOnClickListener { _ ->
            if (entry.isLocalParent) {
                toggleLocalGroup(focusFirstChild = true)
            } else {
                listener?.onItemClicked(entry.groupIndex)
            }
        }

        view.setOnKeyListener { _, keyCode, event: KeyEvent? ->
            if (event?.action == KeyEvent.ACTION_DOWN) {
                if (entry.isLocalParent) {
                    when (keyCode) {
                        KeyEvent.KEYCODE_DPAD_RIGHT,
                        KeyEvent.KEYCODE_DPAD_CENTER,
                        KeyEvent.KEYCODE_ENTER -> {
                            if (!localExpanded) {
                                toggleLocalGroup(focusFirstChild = true)
                            }
                            return@setOnKeyListener true
                        }
                        KeyEvent.KEYCODE_DPAD_LEFT -> {
                            if (localExpanded) {
                                toggleLocalGroup(focusFirstChild = false)
                                return@setOnKeyListener true
                            }
                        }
                    }
                }
                if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN && position == getItemCount() - 1) {
                    val p = 0
                    (recyclerView.layoutManager as? LinearLayoutManager)?.scrollToPositionWithOffset(p, 0)
                    recyclerView.postDelayed({
                        val v = recyclerView.findViewHolderForAdapterPosition(p)
                        v?.itemView?.isSelected = true
                        v?.itemView?.requestFocus()
                    }, 0)
                    return@setOnKeyListener true
                }
                return@setOnKeyListener listener?.onKey(keyCode) ?: false
            }
            false
        }

        viewHolder.bindTitle(entry.title, entry.isLocalChild)
    }

    override fun getItemCount() = displayEntries().size

    class ViewHolder(private val context: Context, private val binding: GroupItemBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bindTitle(text: String, isLocalChild: Boolean) {
            val localizedText = when (text) {
                context.getString(R.string.my_favorites) -> context.getString(R.string.my_favorites)
                context.getString(R.string.all_channels) -> context.getString(R.string.all_channels)
                else -> text
            }
            binding.title.textSize = if (isLocalChild) 16f else 18f
            binding.title.setPadding(if (isLocalChild) 24 else 0, 0, 0, 0)
            binding.title.text = localizedText
        }

        fun focus(hasFocus: Boolean) {
            if (hasFocus) {
                binding.title.setTextColor(ContextCompat.getColor(context, R.color.white))
                binding.root.setBackgroundResource(R.color.focus) // 添加焦点背景
            } else {
                binding.title.setTextColor(ContextCompat.getColor(context, R.color.title_blur))
                binding.root.setBackgroundResource(R.color.transparent) // 设置透明背景
            }
        }
    }

    fun scrollToPositionAndSelect(position: Int) {
        val layoutManager = recyclerView.layoutManager as? LinearLayoutManager
        layoutManager?.let {
            val delay = if (first) {
                100L
            } else {
                first = false
                0
            }

            recyclerView.postDelayed({
                val currentGroupName = tvGroupModel.tvGroupValue.getOrNull(position)?.getName().orEmpty()
                if (isLocalGroup(currentGroupName, position) && !localExpanded) {
                    localExpanded = true
                    notifyDataSetChanged()
                }
                val groupPosition = displayEntries().indexOfFirst { entry ->
                    entry.groupIndex == position
                }.takeIf { displayPosition -> displayPosition >= 0 }
                    ?: 0
                it.scrollToPositionWithOffset(groupPosition, 0)

                val viewHolder = recyclerView.findViewHolderForAdapterPosition(groupPosition)
                viewHolder?.itemView?.apply {
                    isSelected = true
                    requestFocus()
                }
            }, delay)
        }
    }

    interface ItemListener {
        fun onItemFocusChange(listTVModel: TVListModel, hasFocus: Boolean)
        fun onItemClicked(position: Int)
        fun onKey(keyCode: Int): Boolean
    }

    fun setItemListener(listener: ItemListener) {
        this.listener = listener
    }

    fun changed() {
        recyclerView.post {
            notifyDataSetChanged()
        }
    }

    companion object {
        private const val TAG = "GroupAdapter"
        private const val CCTV_GROUP_NAME = "央视"
        private const val SATELLITE_GROUP_NAME = "卫视"
        private const val LOCAL_PARENT_NAME = "地方"
        private const val LOCAL_PARENT_INDEX = -100
    }
}

