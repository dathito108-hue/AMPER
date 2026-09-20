package io.amper.neuroos.core

import java.net.URI
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class OmegaInternetGatewayTest {
    @Test
    fun rejectsNonHttpsAndLocalTargetsBeforeTransport() {
        assertThrows(IllegalArgumentException::class.java) {
            OmegaInternetGateway.requirePublicHttps(
                URI("http://example.com")
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            OmegaInternetGateway.requirePublicHttps(
                URI("https://localhost/test")
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            OmegaInternetGateway.requirePublicHttps(
                URI("https://127.0.0.1/test")
            )
        }
    }

    @Test
    fun htmlExtractionRemovesExecutableMarkup() {
        val text = OmegaInternetGateway.htmlToText(
            "<html><style>x{}</style><script>alert(1)</script>" +
                "<body><h1>AMPER</h1><p>Connected &amp; bounded.</p></body></html>"
        )

        assertTrue("AMPER" in text)
        assertTrue("Connected & bounded." in text)
        assertTrue("alert(1)" !in text)
        assertTrue("<h1>" !in text)
    }

    @Test
    fun internetToolIsReadOnlyAndBounded() {
        val provider = OmegaInternetReadToolProvider(
            OmegaInternetGateway(
                object : OmegaInternetTransport {
                    override fun get(uri: URI): OmegaInternetResponse =
                        OmegaInternetResponse(
                            url = uri.toASCIIString(),
                            statusCode = 200,
                            contentType = "text/plain",
                            text = "ok"
                        )
                }
            )
        )

        assertEquals(ToolSideEffect.READ_ONLY, provider.descriptor.sideEffect)
        assertEquals(
            OmegaInternetGateway.MAX_URL_CHARS,
            provider.descriptor.inputContract.maxLength
        )
        assertEquals(
            OmegaInternetReadToolContract.capability,
            provider.descriptor.capability
        )
    }
}
