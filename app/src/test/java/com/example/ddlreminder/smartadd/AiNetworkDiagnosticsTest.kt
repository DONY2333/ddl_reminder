package com.example.ddlreminder.smartadd

import com.example.ddlreminder.settings.AiSettings
import com.example.ddlreminder.settings.SecureAiSettingsRepository
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.MalformedURLException
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException

@RunWith(RobolectricTestRunner::class)
class AiNetworkDiagnosticsTest {
    private val expected = "https://api.example.com/v1/chat/completions"
    private val config = AiSettings("https://api.example.com/v1", "SECRET_KEY_NEVER_DISPLAY", "model")

    @Test fun `base url trims surrounding whitespace`() {
        assertEquals(expected, HttpAiApiClient.validatedEndpoint(" \n https://api.example.com/v1 \t").toString())
    }
    @Test fun `base url removes enclosing double quotes`() {
        assertEquals(expected, HttpAiApiClient.validatedEndpoint(" \"https://api.example.com/v1\" ").toString())
    }
    @Test fun `base url removes enclosing single quotes`() {
        assertEquals(expected, HttpAiApiClient.validatedEndpoint(" 'https://api.example.com/v1' ").toString())
    }
    @Test fun `normal https and trailing slash give same final endpoint`() {
        assertEquals(expected, HttpAiApiClient.validatedEndpoint(config.baseUrl).toString())
        assertEquals(expected, HttpAiApiClient.validatedEndpoint(config.baseUrl + "/").toString())
    }
    @Test fun `normal http scheme is accepted`() {
        assertEquals("http://localhost:8080/v1/chat/completions", HttpAiApiClient.validatedEndpoint("http://localhost:8080/v1").toString())
    }
    @Test fun `empty host returns URL_INVALID before opening connection`() = invalidUrl("https:///v1")
    @Test fun `malformed url returns URL_INVALID before opening connection`() = invalidUrl("not a url")
    @Test fun `unsupported scheme returns URL_INVALID`() = invalidUrl("ftp://api.example.com/v1")
    @Test fun `url containing credentials or query is rejected rather than exposed`() = runTest {
        listOf("https://user:SECRET@api.example.com/v1", "https://api.example.com/v1?key=SECRET", "https://api.example.com/v1#SECRET").forEach {
            val result = HttpAiApiClient().complete(config.copy(baseUrl = it), "private", "private") as AiApiResult.Failure
            assertEquals(AiApiErrorType.URL_INVALID, result.type)
            assertFalse(result.toString().contains("SECRET"))
        }
    }

    @Test fun `unknown host exception is displayed by exact class`() = verifyException(UnknownHostException("sensitive"))
    @Test fun `connection exception is displayed by exact class`() = verifyException(ConnectException("sensitive"))
    @Test fun `ssl handshake exception is displayed by exact class`() = verifyException(SSLHandshakeException("sensitive"))
    @Test fun `timeout is displayed by exact class`() = verifyException(SocketTimeoutException("sensitive"))
    @Test fun `ssl exception is displayed by exact class`() = verifyException(SSLException("sensitive"))
    @Test fun `io exception is displayed by exact class`() = verifyException(IOException("sensitive"))
    @Test fun `security exception is displayed by exact class`() = verifyException(SecurityException("sensitive"))
    @Test fun `illegal argument is displayed by exact class`() = verifyException(IllegalArgumentException("sensitive"))
    @Test fun `malformed url exception class is preserved`() = verifyException(MalformedURLException("sensitive"))
    @Test fun `other actual exception class is preserved`() = verifyException(UnsupportedOperationException("sensitive"))

    @Test fun `request build failure is distinct and retains concrete class`() = runTest {
        val client = HttpAiApiClient(openConnection = { throw IllegalArgumentException("SECRET_KEY_NEVER_DISPLAY") }, nanoTime = { 0 })
        val failure = client.complete(config, "private", "private") as AiApiResult.Failure
        val text = status(failure).diagnosticText()
        assertEquals(AiApiErrorType.REQUEST_BUILD_FAILED, failure.type)
        assertEquals(0L, failure.latencyMs)
        assertTrue(text.contains("REQUEST_BUILD_FAILED (IllegalArgumentException)"))
        assertTrue(text.contains(expected))
        assertFalse(failure.toString().contains(config.apiKey))
    }

    @Test fun `sub millisecond attempted failure displays one ms not a fake zero`() = runTest {
        val client = HttpAiApiClient(openConnection = { FakeConnection(it) { throw ConnectException() } }, nanoTime = { 0 })
        val failure = client.complete(config, "private", "private") as AiApiResult.Failure
        assertEquals(1L, failure.latencyMs)
    }

    @Test fun `successful diagnostics contain actual url and no response prompt or credentials`() = runTest {
        var nanos = 0L
        val response = "PRIVATE_RESPONSE_USER_DATA"
        val client = HttpAiApiClient(openConnection = { FakeConnection(it, response) { nanos = 23_000_000 } }, nanoTime = { nanos })
        val result = client.complete(config, "PRIVATE_SYSTEM", "PRIVATE_USER") as AiApiResult.Success
        val text = AiRequestStatus(result.statusCode, result.latencyMs, "model", result.endpoint, null).diagnosticText()
        assertEquals(23L, result.latencyMs)
        assertTrue(text.contains("状态码：200"))
        assertTrue(text.contains("Endpoint：$expected"))
        listOf(config.apiKey, "Authorization", "PRIVATE_SYSTEM", "PRIVATE_USER", response).forEach { assertFalse(text.contains(it)) }
    }

    @Test fun `settings save cleans input without altering internal characters`() {
        val repository = SecureAiSettingsRepository(ApplicationProvider.getApplicationContext())
        repository.save(AiSettings(" \"https://api.example.com/v1\" ", "  key internal space  ", "  model internal space  ", true))
        val stored = repository.get()
        assertEquals(config.baseUrl, stored.baseUrl)
        assertEquals("key internal space", stored.apiKey)
        assertEquals("model internal space", stored.modelName)
        assertTrue(stored.privacyAccepted)
    }

    private fun invalidUrl(url: String) = runTest {
        var opened = false
        val client = HttpAiApiClient(openConnection = { opened = true; error("must not connect") })
        val failure = client.complete(config.copy(baseUrl = url), "private", "private") as AiApiResult.Failure
        assertEquals(AiApiErrorType.URL_INVALID, failure.type)
        assertFalse(opened)
        assertTrue(status(failure).errorLabel.startsWith("URL_INVALID"))
    }

    private fun verifyException(error: Exception) = runTest {
        var nanos = 0L
        val client = HttpAiApiClient(openConnection = { FakeConnection(it) { nanos = 12_000_000; throw error } }, nanoTime = { nanos })
        val failure = client.complete(config, "PRIVATE_SYSTEM", "PRIVATE_USER") as AiApiResult.Failure
        val text = status(failure).diagnosticText()
        assertEquals(error.javaClass.simpleName, failure.exceptionClassName)
        assertEquals(error.javaClass.simpleName, status(failure).errorLabel)
        assertEquals(12L, failure.latencyMs)
        assertTrue(text.contains(expected))
        assertTrue(text.contains("状态码：无"))
        listOf(config.apiKey, "Authorization", "PRIVATE_SYSTEM", "PRIVATE_USER", "sensitive").forEach {
            assertFalse(failure.toString().contains(it))
            assertFalse(text.contains(it))
        }
    }

    private fun status(f: AiApiResult.Failure) = AiRequestStatus(f.statusCode, f.latencyMs, "model", f.endpoint, f.type, f.exceptionClassName)
}

private class FakeConnection(url: URL, private val response: String = "{}", private val onStart: () -> Unit) : HttpURLConnection(url) {
    override fun getOutputStream(): ByteArrayOutputStream { onStart(); return ByteArrayOutputStream() }
    override fun getResponseCode() = 200
    override fun getInputStream() = ByteArrayInputStream(response.toByteArray())
    override fun connect() = Unit
    override fun disconnect() = Unit
    override fun usingProxy() = false
}
