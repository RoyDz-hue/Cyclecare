package kotlinx.coroutines

import kotlin.coroutines.Continuation
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine

typealias CancellationException = java.util.concurrent.CancellationException

object Dispatchers { val IO: CoroutineContext = EmptyCoroutineContext }

suspend fun <T> withContext(@Suppress("UNUSED_PARAMETER") context: CoroutineContext, block: suspend () -> T): T = block()

fun <T> runBlocking(block: suspend () -> T): T {
    var result: Result<T>? = null
    block.startCoroutine(object : Continuation<T> {
        override val context: CoroutineContext = EmptyCoroutineContext
        override fun resumeWith(r: Result<T>) { result = r }
    })
    return result!!.getOrThrow()
}
