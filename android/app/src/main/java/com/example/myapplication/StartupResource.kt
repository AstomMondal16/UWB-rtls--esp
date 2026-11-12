package com.example.myapplication

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** Keep startup I/O off the UI thread and release resources if its owner leaves. */
internal suspend fun <T : Any> loadStartupResource(load: () -> T, release: (T) -> Unit): T {
    var pending: T? = null
    try {
        val loaded = withContext(Dispatchers.IO) { load().also { pending = it } }
        pending = null
        return loaded
    } finally {
        pending?.let { resource ->
            withContext(NonCancellable + Dispatchers.IO) { release(resource) }
        }
    }
}
