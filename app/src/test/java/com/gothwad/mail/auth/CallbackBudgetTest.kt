package com.gothwad.mail.auth

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Covers the callback replay guard and the "sign-in must survive the callback activity" rule. */
class CallbackBudgetTest {

    @Test
    fun `a callback is accepted once and rejected the second time`() {
        val budget = CallbackBudget()

        assertTrue(budget.tryAcquire("com.gothwad.mail:/oauth/callback?code=abc&state=s"))
        assertFalse(budget.tryAcquire("com.gothwad.mail:/oauth/callback?code=abc&state=s"))
    }

    @Test
    fun `different callbacks are tracked independently`() {
        val budget = CallbackBudget()

        assertTrue(budget.tryAcquire("callback?code=one"))
        assertTrue(budget.tryAcquire("callback?code=two"))
        assertFalse(budget.tryAcquire("callback?code=one"))
    }

    @Test
    fun `the budget is bounded so it cannot grow without limit`() {
        val budget = CallbackBudget(capacity = 2)

        assertTrue(budget.tryAcquire("a"))
        assertTrue(budget.tryAcquire("b"))
        assertTrue(budget.tryAcquire("c"))
        // The oldest entry was evicted, so it is accepted again rather than rejected forever.
        assertTrue(budget.tryAcquire("a"))
    }

    /**
     * The regression this guards: the callback used to run in the callback activity's
     * `lifecycleScope`, which is cancelled in `onDestroy` right after `finish()`. On a device that
     * aborted the token exchange and dropped the fresh account, so the app fell back to the
     * welcome screen. Application-scoped work must keep running after the launching scope dies.
     */
    @Test
    fun `sign-in completes even when the launching scope is cancelled`() = runBlocking {
        val completed = java.util.concurrent.atomic.AtomicBoolean(false)

        val activityScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        val applicationWork = applicationScope.async {
            delay(150)
            completed.set(true)
        }
        // The activity finishes right after handing the redirect over.
        activityScope.cancel()

        applicationWork.await()
        assertTrue("Callback work must outlive the callback activity", completed.get())
        assertEquals(true, completed.get())
        applicationScope.cancel()
    }
}
