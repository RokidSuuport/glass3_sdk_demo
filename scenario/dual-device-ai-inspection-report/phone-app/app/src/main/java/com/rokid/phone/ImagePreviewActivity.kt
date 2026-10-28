package com.rokid.phone

import android.graphics.Color
import android.os.Bundle
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.bumptech.glide.Glide
import java.io.File

class ImagePreviewActivity : AppCompatActivity() {

    private lateinit var viewPager: ViewPager2
    private lateinit var pageIndicator: TextView
    private val imagePaths = mutableListOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val rootView = FrameLayout(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.BLACK)
        }

        viewPager = ViewPager2(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.BLACK)
        }
        pageIndicator = TextView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.CENTER_HORIZONTAL
            ).apply {
                topMargin = 40
            }
            setBackgroundColor(0x66000000)
            setPadding(24, 10, 24, 10)
            setTextColor(Color.WHITE)
            textSize = 14f
        }

        rootView.addView(viewPager)
        rootView.addView(pageIndicator)
        setContentView(rootView)

        val imagePath = intent.getStringExtra("imagePath")
        val paths = intent.getStringArrayListExtra("imagePaths")
        if (!paths.isNullOrEmpty()) {
            imagePaths.addAll(paths)
        } else if (imagePath != null) {
            imagePaths.add(imagePath)
        }

        if (imagePaths.isEmpty()) {
            finish()
            return
        }

        val startIndex = intent.getIntExtra("imageIndex", 0).coerceIn(imagePaths.indices)
        viewPager.adapter = ImagePreviewAdapter(imagePaths) {
            finish()
        }
        viewPager.setCurrentItem(startIndex, false)
        updatePageIndicator(startIndex)
        viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                updatePageIndicator(position)
            }
        })
    }

    private fun updatePageIndicator(position: Int) {
        pageIndicator.text = "${position + 1} / ${imagePaths.size}"
    }

    private class ImagePreviewAdapter(
        private val imagePaths: List<String>,
        private val onSingleTap: () -> Unit
    ) : RecyclerView.Adapter<ImagePreviewAdapter.ImageViewHolder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ImageViewHolder {
            val imageView = ZoomImageView(parent.context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                scaleType = ImageView.ScaleType.FIT_CENTER
                setBackgroundColor(Color.BLACK)
                setOnSingleTapListener(onSingleTap)
            }
            return ImageViewHolder(imageView)
        }

        override fun onBindViewHolder(holder: ImageViewHolder, position: Int) {
            holder.imageView.resetZoom()
            Glide.with(holder.imageView)
                .load(File(imagePaths[position]))
                .into(holder.imageView)
        }

        override fun getItemCount(): Int = imagePaths.size

        class ImageViewHolder(val imageView: ZoomImageView) : RecyclerView.ViewHolder(imageView)
    }

    private class ZoomImageView(context: android.content.Context) : androidx.appcompat.widget.AppCompatImageView(context) {
        private var isZoomed = false
        private var onSingleTap: (() -> Unit)? = null
        private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                onSingleTap?.invoke()
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                toggleZoom()
                return true
            }
        })

        fun setOnSingleTapListener(listener: () -> Unit) {
            onSingleTap = listener
        }

        fun resetZoom() {
            isZoomed = false
            scaleX = NORMAL_SCALE
            scaleY = NORMAL_SCALE
            translationX = 0f
            translationY = 0f
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            return gestureDetector.onTouchEvent(event) || super.onTouchEvent(event)
        }

        private fun toggleZoom() {
            isZoomed = !isZoomed
            val targetScale = if (isZoomed) ZOOM_SCALE else NORMAL_SCALE
            animate()
                .scaleX(targetScale)
                .scaleY(targetScale)
                .translationX(0f)
                .translationY(0f)
                .setDuration(ZOOM_DURATION)
                .start()
        }

        companion object {
            private const val NORMAL_SCALE = 1f
            private const val ZOOM_SCALE = 2.5f
            private const val ZOOM_DURATION = 180L
        }
    }
}
