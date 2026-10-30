package com.davacada.launcher

import android.annotation.SuppressLint
import android.content.Context
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout

/** Side-by-side pages that snap one screen at a time. */
class AppPager(context: Context) : HorizontalScrollView(context) {

    val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
    var page = 0
        private set
    private var flung = false

    init {
        isHorizontalScrollBarEnabled = false
        overScrollMode = View.OVER_SCROLL_NEVER
        isFillViewport = true
        addView(row, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))
    }

    fun setPages(pages: List<View>) {
        row.removeAllViews()
        val w = width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
        pages.forEach { row.addView(it, LinearLayout.LayoutParams(w, LinearLayout.LayoutParams.MATCH_PARENT)) }
        page = page.coerceIn(0, (pages.size - 1).coerceAtLeast(0))
        post { scrollTo(page * width, 0) }
    }

    fun goTo(target: Int, smooth: Boolean) {
        page = target.coerceIn(0, (row.childCount - 1).coerceAtLeast(0))
        if (smooth) smoothScrollTo(page * width, 0) else scrollTo(page * width, 0)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w != oldw && w > 0) {
            for (i in 0 until row.childCount) row.getChildAt(i).layoutParams.width = w
            row.requestLayout()
            post { scrollTo(page * w, 0) }
        }
    }

    override fun fling(velocityX: Int) {
        flung = true
        val threshold = 600 * resources.displayMetrics.density
        val target = when {
            velocityX > threshold -> page + 1
            velocityX < -threshold -> page - 1
            else -> nearest()
        }
        goTo(target, smooth = true)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) flung = false
        val handled = super.onTouchEvent(ev)
        if ((ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) && !flung) {
            goTo(nearest(), smooth = true)
        }
        return handled
    }

    private fun nearest() = if (width == 0) 0 else (scrollX + width / 2) / width
}

/** Lays its children out in a fixed columns × rows grid that fills the page. */
class FixedGrid(context: Context, private val columns: Int, private val rows: Int, private val cell: Int) :
    ViewGroup(context) {

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        val spec = MeasureSpec.makeMeasureSpec(cell, MeasureSpec.EXACTLY)
        for (i in 0 until childCount) getChildAt(i).measure(spec, spec)
        setMeasuredDimension(w, h)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val innerW = (r - l) - paddingLeft - paddingRight
        val innerH = (b - t) - paddingTop - paddingBottom
        val cw = innerW / columns.toFloat()
        val ch = innerH / rows.toFloat()
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            val col = i % columns
            val rowIdx = i / columns
            val cx = paddingLeft + cw * (col + 0.5f)
            val cy = paddingTop + ch * (rowIdx + 0.5f)
            val left = (cx - c.measuredWidth / 2f).toInt()
            val top = (cy - c.measuredHeight / 2f).toInt()
            c.layout(left, top, left + c.measuredWidth, top + c.measuredHeight)
        }
    }
}
