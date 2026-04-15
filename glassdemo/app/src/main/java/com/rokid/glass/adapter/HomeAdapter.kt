package com.rokid.glass.adapter

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Resources
import android.graphics.Typeface
import android.util.Log
import android.view.LayoutInflater

import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.recyclerview.widget.RecyclerView
import com.rokid.glass.component.MenuItem
import com.rokid.glass.recycleview.BaseViewHolder
import com.rokid.glesse.R


/**
 * Author: zhangshengwei
 * Date: 2021/1/23
 */
class HomeAdapter(private val mContext: Context) : RecyclerView.Adapter<HomeAdapter.ViewHolder>() {
    private val iconInfoList: MutableList<MenuItem> = ArrayList()
    private var lastFocusPosition = 0
    private var TAG = "HomeAdapter"

    fun setIconInfoList(iconInfoList: List<MenuItem>?) {
        this.iconInfoList.clear()
        this.iconInfoList.addAll(iconInfoList!!)
        this.focusPosition = 0
    }

    private var itemOnClickListener: ItemOnClickListener? = null
    fun setItemOnClickListener(itemOnClickListener: ItemOnClickListener?) {
        this.itemOnClickListener = itemOnClickListener
    }

    private var focusPosition = 0
    fun getFocusPosition(): Int {
        return focusPosition
    }

    fun setFocusPosition(focusPosition: Int) {
        this.focusPosition = focusPosition
        iconInfoList[focusPosition].isChecked = true
    }

    //翻到下一页
    private val _toNextPage = MutableLiveData<Unit>()
    val toNextPage: LiveData<Unit> = _toNextPage
    private fun toNextPage() {
        _toNextPage.postValue(Unit)
    }

    //翻到上一页
    private val _toPreviousPage = MutableLiveData<Unit>()
    val toPreviousPage: LiveData<Unit> = _toPreviousPage
    private fun toPreviousPage() {
        _toPreviousPage.postValue(Unit)
    }

    fun toNextItem() {
        if ((focusPosition < iconInfoList.size - 1) && focusPosition >= 0) {
            lastFocusPosition = this.focusPosition
            focusPosition++
            iconInfoList.forEach {
                it.isChecked = false
            }
            iconInfoList[focusPosition].isChecked = true
            notifyDataSetChanged()
            Log.d(TAG, "toNextItem---focusPosition=$focusPosition")
        } else if (focusPosition >= iconInfoList.size - 1) {
            Log.d(TAG, "准备进入下一页")
            iconInfoList.forEach {
                it.isChecked = false
            }
            toNextPage()
        }

    }

    fun toPreviousItem() {
        if (focusPosition >= 1) {
            lastFocusPosition = this.focusPosition
            focusPosition--
            iconInfoList.forEach {
                it.isChecked = false
            }
            iconInfoList[focusPosition].isChecked = true
            notifyDataSetChanged()
            Log.d(TAG, "toPreviousItem---focusPosition=$focusPosition")
        } else {
            iconInfoList.forEach {
                it.isChecked = false
            }
            Log.d(TAG, "准备进入上一页")
            toPreviousPage()
        }
    }


    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val itemView = LayoutInflater.from(parent.context).inflate(R.layout.item_main_icon, parent, false)
        return ViewHolder(itemView)
    }

    override fun onBindViewHolder(holder: ViewHolder, @SuppressLint("RecyclerView") position: Int) {
        if (!iconInfoList.isEmpty()) {
            val menuItem = iconInfoList[position]
            val isChecked = menuItem.isChecked
            holder.iconNameTv.setTypeface(Typeface.DEFAULT, if (isChecked) Typeface.BOLD else Typeface.NORMAL)
            holder.iconNameTv.text = menuItem.menuName

            holder.layoutItem.animate().cancel()

            if ((lastFocusPosition == position) != isChecked) {
                val targetY = if (isChecked) -12.dpToPx().toFloat() else 0f
                holder.layoutItem.animate().translationY(targetY).setDuration(300).start()
            } else {
                holder.layoutItem.translationY = if (isChecked) -12.dpToPx().toFloat() else 0f
            }
            if (isChecked) {
                holder.iconImage.setImageResource(menuItem.checkedIconId)
                holder.layoutItem.background = ContextCompat.getDrawable(mContext, menuItem.checkedItemBgId)
                holder.iconSelectBar.visibility = View.VISIBLE
            } else {
                holder.iconImage.setImageResource(menuItem.unCheckIconId)
                holder.layoutItem.background = ContextCompat.getDrawable(mContext, menuItem.unCheckItemBgId)
                holder.iconSelectBar.visibility = View.GONE
            }
            holder.itemView.setOnClickListener { v: View? ->
                Log.d(TAG, "setOnClickListener-" + " focusPosition:" + focusPosition + " hasFocus:" + (holder.itemView.hasFocus()))
                if (null != itemOnClickListener) {
                    itemOnClickListener!!.onClick(focusPosition, v)
                }
            }
            if (position == 0) {
                holder.itemView.requestFocus()
                iconInfoList[0].isChecked = true
            }
        }
    }

    override fun getItemCount(): Int {
        return iconInfoList.size
    }

    fun Int.dpToPx(): Int = (this * Resources.getSystem().displayMetrics.density).toInt()

    inner class ViewHolder(itemView: View) : BaseViewHolder(itemView) {
        val layoutItem: View
        val iconImage: ImageView
        val iconSelectBar: ImageView
        val iconNameTv: TextView

        init {
            layoutItem = itemView.findViewById(R.id.main_view)
            iconImage = itemView.findViewById(R.id.main_icon_image)
            iconSelectBar = itemView.findViewById(R.id.item_main_select_bar)
            iconNameTv = itemView.findViewById(R.id.main_icon_name_tv)
        }
    }

}