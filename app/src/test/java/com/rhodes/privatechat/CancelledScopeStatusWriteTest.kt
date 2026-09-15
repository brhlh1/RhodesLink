package com.rhodes.privatechat

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Proves why a cancelled knowledge-base indexing run could never repair its own status:
 * `withContext(Dispatchers.Default)` re-checks cancellation on entry, so the write was skipped and the
 * book stayed at "indexing:x/y" forever. NonCancellable is what makes the repair possible.
 *
 * The coroutine must start UNDISPATCHED: otherwise the body never runs before cancel() and both tests
 * would "pass" for the wrong reason.
 */
class CancelledScopeStatusWriteTest {
    @Test
    fun plainWithContextWriteIsSkippedInACancelledScope() = runBlocking {
        var written = false
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                awaitCancellation()
            } finally {
                withContext(Dispatchers.Default) { written = true }
            }
        }
        job.cancelAndJoin()
        assertFalse("取消后的 withContext 不会执行 —— 这正是状态停在 indexing:x/y 的原因", written)
    }

    @Test
    fun nonCancellableWriteStillRunsAfterCancellation() = runBlocking {
        var written = false
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                awaitCancellation()
            } finally {
                withContext(NonCancellable + Dispatchers.Default) { written = true }
            }
        }
        job.cancelAndJoin()
        assertTrue("NonCancellable 才能让状态修复落盘", written)
    }
}
