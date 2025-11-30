package com.rifters.riftedreader

import com.rifters.riftedreader.ui.reader.WebViewJsBridge
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for WebViewJsBridge.
 *
 * Tests lifecycle-safe JS evaluation and callback invocation behavior.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WebViewJsBridgeTest {

    @Test
    fun `pending callbacks are invoked with null when onDestroyView is called`() {
        val bridge = WebViewJsBridge()
        
        // Track callback invocations
        val callbacks = mutableListOf<String?>()
        
        // Queue multiple JS calls (without setting ready, they will be pending)
        bridge.evaluate("test1()") { result -> callbacks.add(result) }
        bridge.evaluate("test2()") { result -> callbacks.add(result) }
        bridge.evaluate("test3()") { result -> callbacks.add(result) }
        
        // Verify nothing has been invoked yet
        assertEquals(0, callbacks.size)
        
        // Call onDestroyView - should invoke all callbacks with null
        bridge.onDestroyView()
        
        // Verify all callbacks were invoked with null
        assertEquals(3, callbacks.size)
        assertTrue(callbacks.all { it == null })
    }

    @Test
    fun `callback invoked with null when evaluate is called after destroy`() {
        val bridge = WebViewJsBridge()
        bridge.onDestroyView()
        
        var callbackInvoked = false
        var callbackResult: String? = "not-null"
        
        // Evaluate after destroy
        bridge.evaluate("test()") { result ->
            callbackInvoked = true
            callbackResult = result
        }
        
        // Callback should be invoked immediately with null
        assertTrue(callbackInvoked)
        assertNull(callbackResult)
    }

    @Test
    fun `evaluateAsync does not hang when onDestroyView is called`() = runTest {
        val bridge = WebViewJsBridge()
        
        // Start an async evaluation in the background
        val deferred = async {
            bridge.evaluateAsync("test()")
        }
        
        // Give coroutine time to start
        testScheduler.advanceUntilIdle()
        
        // Destroy the bridge - should resume the suspended coroutine with null
        bridge.onDestroyView()
        
        // The deferred should complete (not hang) and return null
        val result = deferred.await()
        assertNull(result)
    }

    @Test
    fun `canEvaluate returns false when destroyed`() {
        val bridge = WebViewJsBridge()
        bridge.setActive(true)
        // Note: Can't test setReady(true) without a real WebView
        
        // Destroy the bridge
        bridge.onDestroyView()
        
        // Should return false when destroyed
        assertFalse(bridge.canEvaluate())
    }

    @Test
    fun `diagnostics show pending count correctly`() {
        val bridge = WebViewJsBridge()
        
        // Initially no pending
        assertEquals(0, bridge.getDiagnostics()["pendingCount"])
        
        // Queue some calls
        bridge.evaluate("test1()") { }
        bridge.evaluate("test2()") { }
        
        // Should show 2 pending
        assertEquals(2, bridge.getDiagnostics()["pendingCount"])
        
        // After destroy, pending should be 0
        bridge.onDestroyView()
        assertEquals(0, bridge.getDiagnostics()["pendingCount"])
    }

    @Test
    fun `diagnostics track cancelled count correctly`() {
        val bridge = WebViewJsBridge()
        
        // Initially no cancelled
        assertEquals(0, bridge.getDiagnostics()["totalCancelled"])
        
        // Queue some calls and destroy
        bridge.evaluate("test1()") { }
        bridge.evaluate("test2()") { }
        bridge.onDestroyView()
        
        // Should show 2 cancelled
        assertEquals(2, bridge.getDiagnostics()["totalCancelled"])
    }

    @Test
    fun `callbacks with no callback function are handled gracefully`() {
        val bridge = WebViewJsBridge()
        
        // Queue a call without a callback
        bridge.evaluate("test()", null)
        
        // Should not crash when destroyed
        bridge.onDestroyView()
        
        // Verify the bridge is in expected state
        assertTrue(bridge.getDiagnostics()["isDestroyed"] as Boolean)
    }
}
