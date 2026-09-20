package io.amper.neuroos.core

import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI
import java.net.URL
import java.nio.charset.Charset

data class OmegaInternetResponse(
    val url: String,
    val statusCode: Int,
    val contentType: String,
    val text: String
) {
    init {
        require(url.startsWith("https://"))
        require(statusCode in 100..599)
        require(contentType.isNotBlank())
        require(text.length <= OmegaInternetGateway.MAX_TEXT_CHARS)
    }
}

interface OmegaInternetTransport {
    fun get(uri: URI): OmegaInternetResponse
}

class UrlConnectionOmegaInternetTransport : OmegaInternetTransport {
    override fun get(uri: URI): OmegaInternetResponse {
        OmegaInternetGateway.requirePublicHttps(uri)

        val connection = (URL(uri.toASCIIString()).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = false
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            requestMethod = "GET"
            setRequestProperty(
                "User-Agent",
                "AMPER-Mobile-Omega/2 (+local governed internet tool)"
            )
            setRequestProperty(
                "Accept",
                "text/plain,text/html,application/json,application/xml,text/xml,application/xhtml+xml"
            )
            setRequestProperty("Accept-Encoding", "identity")
        }

        return try {
            val status = connection.responseCode
            require(status in 200..299) {
                "internet read returned HTTP $status; redirects and non-success responses are not followed"
            }

            val rawType = connection.contentType.orEmpty()
            val mediaType = rawType.substringBefore(';').trim().lowercase()
            require(mediaType in ALLOWED_MEDIA_TYPES || mediaType.startsWith("text/")) {
                "unsupported internet content type: ${if (mediaType.isBlank()) "unknown" else mediaType}"
            }

            val charset = parseCharset(rawType)
            val bytes = connection.inputStream.use { input ->
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(8 * 1024)
                var total = 0
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total = Math.addExact(total, read)
                    require(total <= OmegaInternetGateway.MAX_RESPONSE_BYTES) {
                        "internet response exceeds ${OmegaInternetGateway.MAX_RESPONSE_BYTES} bytes"
                    }
                    out.write(buffer, 0, read)
                }
                out.toByteArray()
            }

            val decoded = bytes.toString(charset)
            val text = if (
                mediaType == "text/html" ||
                mediaType == "application/xhtml+xml"
            ) {
                OmegaInternetGateway.htmlToText(decoded)
            } else {
                decoded
            }.trim().take(OmegaInternetGateway.MAX_TEXT_CHARS)

            require(text.isNotBlank()) {
                "internet response contains no usable text"
            }

            OmegaInternetResponse(
                url = uri.toASCIIString(),
                statusCode = status,
                contentType = mediaType.ifBlank { "text/plain" },
                text = text
            )
        } finally {
            connection.disconnect()
        }
    }

    private fun parseCharset(contentType: String): Charset {
        val value = contentType
            .split(';')
            .asSequence()
            .map(String::trim)
            .firstOrNull { it.startsWith("charset=", ignoreCase = true) }
            ?.substringAfter('=')
            ?.trim()
            ?.trim('"')
        return runCatching {
            if (value.isNullOrBlank()) Charsets.UTF_8 else Charset.forName(value)
        }.getOrDefault(Charsets.UTF_8)
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 8_000
        private const val READ_TIMEOUT_MS = 10_000

        private val ALLOWED_MEDIA_TYPES = setOf(
            "application/json",
            "application/xml",
            "application/xhtml+xml",
            "application/rss+xml",
            "application/atom+xml"
        )
    }
}

class OmegaInternetGateway(
    private val transport: OmegaInternetTransport = UrlConnectionOmegaInternetTransport()
) {
    fun read(url: String): Result<OmegaInternetResponse> = runCatching {
        require(url.length <= MAX_URL_CHARS) {
            "internet URL exceeds $MAX_URL_CHARS characters"
        }
        require('\n' !in url && '\r' !in url && '\u0000' !in url) {
            "internet URL must be one valid line"
        }
        val uri = URI(url.trim())
        requirePublicHttps(uri)
        transport.get(uri)
    }

    companion object {
        const val MAX_URL_CHARS = 2_048
        const val MAX_RESPONSE_BYTES = 128 * 1024
        const val MAX_TEXT_CHARS = 24_000

        fun requirePublicHttps(uri: URI) {
            require(uri.scheme.equals("https", ignoreCase = true)) {
                "AMPER internet read requires https"
            }
            require(uri.userInfo == null) {
                "AMPER internet read does not allow URL credentials"
            }
            require(uri.fragment == null || uri.fragment.length <= 256) {
                "URL fragment is too long"
            }
            require(uri.port == -1 || uri.port == 443) {
                "AMPER internet read allows only the default HTTPS port"
            }

            val host = requireNotNull(uri.host) {
                "AMPER internet read requires a host"
            }.trimEnd('.')
            require(host.isNotBlank()) {
                "AMPER internet read requires a host"
            }
            require(
                !host.equals("localhost", ignoreCase = true) &&
                    !host.endsWith(".localhost", ignoreCase = true) &&
                    !host.endsWith(".local", ignoreCase = true)
            ) {
                "AMPER internet read rejects local hosts"
            }

            val addresses = InetAddress.getAllByName(host)
            require(addresses.isNotEmpty()) {
                "AMPER internet host did not resolve"
            }
            addresses.forEach { address ->
                require(
                    !address.isAnyLocalAddress &&
                        !address.isLoopbackAddress &&
                        !address.isLinkLocalAddress &&
                        !address.isSiteLocalAddress &&
                        !isUniqueLocalIpv6(address)
                ) {
                    "AMPER internet read rejects private/local network destinations"
                }
            }
        }

        private fun isUniqueLocalIpv6(address: InetAddress): Boolean {
            if (address !is Inet6Address) return false
            val first = address.address.first().toInt() and 0xff
            return first and 0xfe == 0xfc
        }

        fun htmlToText(html: String): String = html
            .replace(Regex("(?is)<script\\b[^>]*>.*?</script>"), " ")
            .replace(Regex("(?is)<style\\b[^>]*>.*?</style>"), " ")
            .replace(Regex("(?is)<noscript\\b[^>]*>.*?</noscript>"), " ")
            .replace(Regex("(?is)<[^>]+>"), " ")
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace(Regex("[ \\t\\x0B\\f\\r]+"), " ")
            .replace(Regex("\\n[ \\t]+"), "\n")
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()
    }
}

object OmegaInternetReadToolContract {
    val capability = CapabilityId("internet.read.https")
    val toolId = ToolId("amper-omega-internet-reader")
}

class OmegaInternetReadToolProvider(
    private val gateway: OmegaInternetGateway
) : ToolProvider {
    override val descriptor = ToolDescriptor(
        id = OmegaInternetReadToolContract.toolId,
        name = "AMPER governed internet reader",
        capability = OmegaInternetReadToolContract.capability,
        sideEffect = ToolSideEffect.READ_ONLY,
        inputContract = ToolInputContract(
            description =
                "Read one public HTTPS text/HTML/JSON/XML URL through AMPER; input is the exact https:// URL",
            maxLength = OmegaInternetGateway.MAX_URL_CHARS
        )
    )

    override fun execute(input: String): Result<String> =
        gateway.read(input).map { response ->
            buildString {
                appendLine("url=${response.url}")
                appendLine("status=${response.statusCode}")
                appendLine("content_type=${response.contentType}")
                appendLine("content:")
                append(response.text)
            }
        }
}
