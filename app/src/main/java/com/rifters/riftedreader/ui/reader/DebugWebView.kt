package com.rifters.riftedreader.ui.reader

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.webkit.WebView
import com.rifters.riftedreader.util.AppLogger

/**
 * Custom WebView that logs touch event handling for debugging gesture interactions.
 * This helps diagnose touch event flow between WebView and parent ViewPager2.
 */
class DebugWebView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : WebView(context, attrs, defStyleAttr) {

    private var lastTouchEventTime: Long = 0
    private var lastTouchX: Float = 0f
    private var lastTouchY: Float = 0f

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val currentTime = System.currentTimeMillis()
        val deltaTime = if (lastTouchEventTime > 0) currentTime - lastTouchEventTime else 0
        val deltaX = event.x - lastTouchX
        val deltaY = event.y - lastTouchY
        
        lastTouchEventTime = currentTime
        lastTouchX = event.x
        lastTouchY = event.y

        val actionMasked = event.actionMasked
        val actionName = getActionName(actionMasked)
        val pointerCount = event.pointerCount
        val pointerIndex = event.actionIndex
        val pointerId = if (pointerCount > pointerIndex) event.getPointerId(pointerIndex) else -1
        
        val result = super.onTouchEvent(event)
        
        AppLogger.d(
            "DebugWebView",
            "onTouchEvent: action=$actionName(masked=$actionMasked) " +
                    "x=${event.x} y=${event.y} deltaX=$deltaX deltaY=$deltaY deltaTime=${deltaTime}ms " +
                    "pointerCount=$pointerCount pointerIndex=$pointerIndex pointerId=$pointerId " +
                    "timestamp=$currentTime RETURNED=$result [WEBVIEW_TOUCH_EVENT]"
        )
        
        if (actionMasked == MotionEvent.ACTION_CANCEL) {
            AppLogger.d(
                "DebugWebView",
                "ACTION_CANCEL detected in onTouchEvent - gesture cancelled by WebView [CANCEL_SOURCE]"
            )
        }
        
        return result
    }

    override fun requestDisallowInterceptTouchEvent(disallowIntercept: Boolean) {
        val timestamp = System.currentTimeMillis()
        
        AppLogger.d(
            "DebugWebView",
            "requestDisallowInterceptTouchEvent($disallowIntercept) called at timestamp=$timestamp " +
                    "${if (disallowIntercept) "[CLAIM_GESTURE]" else "[RELEASE_GESTURE]"}"
        )
        
        super.requestDisallowInterceptTouchEvent(disallowIntercept)
    }

    private fun getActionName(action: Int): String = when (action) {
        MotionEvent.ACTION_DOWN -> "DOWN"
        MotionEvent.ACTION_UP -> "UP"
        MotionEvent.ACTION_MOVE -> "MOVE"
        MotionEvent.ACTION_CANCEL -> "CANCEL"
        MotionEvent.ACTION_POINTER_DOWN -> "POINTER_DOWN"
        MotionEvent.ACTION_POINTER_UP -> "POINTER_UP"
        MotionEvent.ACTION_OUTSIDE -> "OUTSIDE"
        else -> "OTHER($action)"
    }
}
