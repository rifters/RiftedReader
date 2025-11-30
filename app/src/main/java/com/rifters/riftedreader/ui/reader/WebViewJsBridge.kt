package com.rifters.riftedreader.ui.reader

import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import com.rifters.riftedreader.util.AppLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Safe bridge for JavaScript evaluation on WebView with lifecycle awareness.
 *
 * **Problem Solved:**
 * Evaluating JavaScript on a WebView after its Fragment is destroyed causes crashes.
 * This bridge:
 * 1. Queues JS calls until WebView is ready (onPageFinished + paginator initialized)
 * 2. Tracks active state (onResume/onPause) and blocks calls when inactive
 * 3. Cancels all pending JS tasks on onDestroyView
 * 4. Wraps all evaluateJavascript calls in try/catch with null checks
 *
 * **Usage:**
 * ```kotlin
 * class MyFragment : Fragment() {
 *     private val jsBridge = WebViewJsBridge()
 *
 *     override fun onResume() {
 *         super.onResume()
 *         jsBridge.setActive(true)
 *     }
 *
 *     override fun onPause() {
 *         super.onPause()
 *         jsBridge.setActive(false)
 *     }
 *
 *     override fun onDestroyView() {
 *         jsBridge.onDestroyView()
 *         super.onDestroyView()
 *     }
 *
 *     // When WebView is ready:
 *     jsBridge.setReady(true, webView)
 *
 *     // Evaluate JS safely:
 *     val result = jsBridge.evaluate("window.myFunction()")
 * }
 * ```
 *
 * @see WebViewPaginatorBridge for higher-level paginator operations
 */
class WebViewJsBridge {

    private val mainHandler = Handler(Looper.getMainLooper())

    /** Whether the Fragment is active (between onResume and onPause) */
    private val isActive = AtomicBoolean(false)

    /** Whether the WebView is ready for JS evaluation */
    private val isReady = AtomicBoolean(false)

    /** Whether onDestroyView has been called */
    private val isDestroyed = AtomicBoolean(false)

    /** Reference to the WebView (only valid when ready) */
    @Volatile
    private var webViewRef: WebView? = null

    /** Queue of pending JS commands waiting for ready state */
    private val pendingQueue = ConcurrentLinkedQueue<PendingJsCall>()

    /** Counter for tracking evaluation attempts (for diagnostics) */
    private val evaluationCounter = AtomicInteger(0)

    /** Counter for cancelled evaluations */
    private val cancelledCounter = AtomicInteger(0)

    /**
     * Pending JS call stored in queue.
     */
    private data class PendingJsCall(
        val expression: String,
        val callback: ((String?) -> Unit)?,
        val timestamp: Long = System.currentTimeMillis()
    )

    /**
     * Set the active state (call from onResume/onPause).
     *
     * @param active true in onResume, false in onPause
     */
    fun setActive(active: Boolean) {
        isActive.set(active)
        AppLogger.d(TAG, "[WebViewBridge] setActive=$active")

        // Flush pending queue if now active and ready
        if (active && isReady.get() && !isDestroyed.get()) {
            flushPendingQueue()
        }
    }

    /**
     * Set the ready state and WebView reference.
     * Call this after onPageFinished and paginator initialization.
     *
     * @param ready true when WebView is ready for JS
     * @param webView The WebView instance (required when ready=true)
     */
    fun setReady(ready: Boolean, webView: WebView? = null) {
        if (isDestroyed.get()) {
            AppLogger.w(TAG, "[WebViewBridge] setReady called after destroy, ignoring")
            return
        }

        if (ready && webView == null) {
            AppLogger.e(TAG, "[WebViewBridge] setReady(true) called without WebView")
            return
        }

        isReady.set(ready)
        webViewRef = if (ready) webView else null
        AppLogger.d(TAG, "[WebViewBridge] setReady=$ready, webView=${webView != null}")

        // Flush pending queue if now ready and active
        if (ready && isActive.get()) {
            flushPendingQueue()
        }
    }

    /**
     * Called from Fragment.onDestroyView().
     * Cancels all pending JS tasks and clears state.
     * Invokes all pending callbacks with null to prevent coroutines from hanging indefinitely.
     */
    fun onDestroyView() {
        isDestroyed.set(true)
        isActive.set(false)
        isReady.set(false)
        webViewRef = null

        // Cancel all pending tasks and invoke their callbacks with null
        var cancelledCount = 0
        while (true) {
            val call = pendingQueue.poll() ?: break
            cancelledCount++
            call.callback?.invoke(null)
        }
        cancelledCounter.addAndGet(cancelledCount)

        AppLogger.d(TAG, "[WebViewBridge] onDestroyView: cancelled $cancelledCount pending tasks, " +
            "total evaluations=${evaluationCounter.get()}, total cancelled=${cancelledCounter.get()}")
    }

    /**
     * Check if the bridge is currently able to evaluate JS.
     *
     * @return true if active, ready, and not destroyed
     */
    fun canEvaluate(): Boolean {
        return isActive.get() && isReady.get() && !isDestroyed.get() && webViewRef != null
    }

    /**
     * Evaluate JavaScript immediately if ready, otherwise queue for later.
     *
     * @param expression The JavaScript expression to evaluate
     * @param callback Optional callback for the result
     */
    fun evaluate(expression: String, callback: ((String?) -> Unit)? = null) {
        evaluationCounter.incrementAndGet()

        if (isDestroyed.get()) {
            AppLogger.w(TAG, "[WebViewBridge] evaluate() called after destroy, dropping: ${expression.take(50)}")
            cancelledCounter.incrementAndGet()
            callback?.invoke(null)
            return
        }

        if (canEvaluate()) {
            executeNow(expression, callback)
        } else {
            // Queue for later execution
            pendingQueue.offer(PendingJsCall(expression, callback))
            AppLogger.d(TAG, "[WebViewBridge] Queued JS (pending=${pendingQueue.size}): ${expression.take(50)}")
        }
    }

    /**
     * Evaluate JavaScript and suspend until result is available.
     *
     * @param expression The JavaScript expression to evaluate
     * @return The result string or null if evaluation failed
     * @throws CancellationException if the coroutine is cancelled
     */
    suspend fun evaluateAsync(expression: String): String? = suspendCancellableCoroutine { continuation ->
        evaluate(expression) { result ->
            if (continuation.isActive) {
                continuation.resume(result)
            }
        }

        continuation.invokeOnCancellation {
            cancelledCounter.incrementAndGet()
            AppLogger.d(TAG, "[WebViewBridge] evaluateAsync cancelled: ${expression.take(50)}")
        }
    }

    /**
     * Execute JavaScript immediately without queuing.
     * Only call this if you're sure the WebView is ready.
     */
    private fun executeNow(expression: String, callback: ((String?) -> Unit)?) {
        val webView = webViewRef
        if (webView == null) {
            AppLogger.w(TAG, "[WebViewBridge] executeNow: webViewRef is null")
            callback?.invoke(null)
            return
        }

        mainHandler.post {
            try {
                if (isDestroyed.get()) {
                    AppLogger.w(TAG, "[WebViewBridge] executeNow: destroyed during post")
                    cancelledCounter.incrementAndGet()
                    callback?.invoke(null)
                    return@post
                }

                webView.evaluateJavascript(expression) { result ->
                    val cleanResult = result?.trim()?.removeSurrounding("\"")
                    AppLogger.d(TAG, "[WebViewBridge] JS result: ${cleanResult?.take(100)}")
                    callback?.invoke(cleanResult)
                }
            } catch (e: Exception) {
                AppLogger.e(TAG, "[WebViewBridge] evaluateJavascript exception: ${e.message}", e)
                cancelledCounter.incrementAndGet()
                callback?.invoke(null)
            }
        }
    }

    /**
     * Flush the pending queue by executing all queued JS calls.
     */
    private fun flushPendingQueue() {
        if (!canEvaluate()) return

        var flushed = 0
        while (true) {
            val call = pendingQueue.poll() ?: break
            flushed++
            executeNow(call.expression, call.callback)
        }

        if (flushed > 0) {
            AppLogger.d(TAG, "[WebViewBridge] Flushed $flushed pending JS calls")
        }
    }

    /**
     * Get diagnostic information about the bridge state.
     */
    fun getDiagnostics(): Map<String, Any> {
        return mapOf(
            "isActive" to isActive.get(),
            "isReady" to isReady.get(),
            "isDestroyed" to isDestroyed.get(),
            "hasWebView" to (webViewRef != null),
            "pendingCount" to pendingQueue.size,
            "totalEvaluations" to evaluationCounter.get(),
            "totalCancelled" to cancelledCounter.get()
        )
    }

    companion object {
        private const val TAG = "WebViewJsBridge"
    }
}
