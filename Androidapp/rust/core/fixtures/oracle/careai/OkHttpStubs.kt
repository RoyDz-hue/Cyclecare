package okhttp3

import java.io.Closeable
import java.io.IOException
import java.util.concurrent.TimeUnit

class MediaType private constructor(val value: String) {
    companion object { fun String.toMediaType(): MediaType = MediaType(this) }
}

class RequestBody private constructor(val content: String, val type: MediaType) {
    companion object { fun String.toRequestBody(type: MediaType): RequestBody = RequestBody(this, type) }
}

class Request private constructor(val url: String, val headers: Map<String, String>) {
    class Builder {
        private var u = ""
        private val h = linkedMapOf<String, String>()
        private var body: RequestBody? = null
        fun url(v: String) = apply { u = v }
        fun header(k: String, v: String) = apply { h[k] = v }
        fun post(b: RequestBody) = apply { body = b }
        fun get() = apply {}
        fun build() = Request(u, h)
    }
}

class Response(val isSuccessful: Boolean, val body: ResponseBody?) : Closeable {
    override fun close() {}
}

class ResponseBody(private val bytes: ByteArray) {
    fun source(): okio.Source = okio.ByteArraySource(bytes)
    fun contentLength(): Long = bytes.size.toLong()
    fun byteStream(): java.io.InputStream = java.io.ByteArrayInputStream(bytes)
}

interface Call { fun execute(): Response }

class OkHttpClient private constructor() {
    fun newCall(@Suppress("UNUSED_PARAMETER") request: Request): Call = object : Call {
        override fun execute(): Response = throw IOException("probe: no network")
    }
    class Builder {
        fun connectTimeout(t: Long, u: TimeUnit) = this
        fun readTimeout(t: Long, u: TimeUnit) = this
        fun callTimeout(t: Long, u: TimeUnit) = this
        fun followRedirects(b: Boolean) = this
        fun followSslRedirects(b: Boolean) = this
        fun build() = OkHttpClient()
    }
}
