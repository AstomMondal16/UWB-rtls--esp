package com.example.myapplication

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class StartupResourceTest {
    @Test fun loadingRunsOffTheCallerThreadAndTransfersOwnership() = runBlocking {
        val caller = Thread.currentThread()
        val worker = AtomicReference<Thread>()
        val closes = AtomicInteger()
        val resource = Any()
        val loaded = loadStartupResource(
            load = { worker.set(Thread.currentThread()); resource },
            release = { closes.incrementAndGet() })
        assertSame(resource, loaded)
        assertNotSame(caller, worker.get())
        assertSame(caller, Thread.currentThread())
        assertEquals(0, closes.get())
    }

    @Test fun leavingDuringLoadingClosesTheResourceWithoutDeliveringIt() = runBlocking {
        val caller = Thread.currentThread()
        val entered = CountDownLatch(1)
        val finish = CountDownLatch(1)
        val closes = AtomicInteger()
        val cleanupThread = AtomicReference<Thread>()
        var delivered = false
        val resource = Any()
        val job = launch {
            loadStartupResource(
                load = {
                    entered.countDown()
                    check(finish.await(5, TimeUnit.SECONDS))
                    resource
                },
                release = {
                    assertSame(resource, it)
                    cleanupThread.set(Thread.currentThread())
                    closes.incrementAndGet()
                })
            delivered = true
        }
        try {
            withContext(Dispatchers.IO) { assertTrue(entered.await(5, TimeUnit.SECONDS)) }
            job.cancel()
        } finally {
            finish.countDown()
            job.join()
        }
        assertFalse(delivered)
        assertEquals(1, closes.get())
        assertNotSame(caller, cleanupThread.get())
    }

    @Test fun aLoadFailureIsReportedAndCanBeRetried() = runBlocking {
        val failure = IllegalStateException("Cannot open saved recording")
        val closes = AtomicInteger()
        try {
            loadStartupResource<Any>(load = { throw failure }, release = { closes.incrementAndGet() })
            fail("Expected startup failure")
        } catch (error: IllegalStateException) {
            assertEquals(failure.javaClass, error.javaClass)
            assertEquals(failure.message, error.message)
        }
        val retry = Any()
        assertSame(retry, loadStartupResource(load = { retry }, release = { closes.incrementAndGet() }))
        assertEquals(0, closes.get())
    }
}
