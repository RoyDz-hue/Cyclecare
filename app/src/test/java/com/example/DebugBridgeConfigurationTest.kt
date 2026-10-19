package com.example

import com.example.domain.diagnostics.DebugBridgeConfiguration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DebugBridgeConfigurationTest {
    @Test
    fun unconfiguredDiagnosticsRemainUnavailable() {
        for (value in listOf("", " ", "\n\t")) {
            assertNull(DebugBridgeConfiguration.baseUrl(true, value))
        }
    }

    @Test
    fun releaseNeverAcceptsEvenAValidDebugOrigin() {
        assertNull(DebugBridgeConfiguration.baseUrl(false, "https://diagnostics.example.test"))
    }

    @Test
    fun explicitHttpsOriginIsNormalizedWithoutChangingItsDestination() {
        assertEquals("https://diagnostics.example.test", DebugBridgeConfiguration.baseUrl(true, " https://diagnostics.example.test/ "))
        assertEquals("https://diagnostics.example.test:8443", DebugBridgeConfiguration.baseUrl(true, "https://diagnostics.example.test:8443"))
    }

    @Test
    fun plaintextAndMalformedOriginsAreRejected() {
        for (value in listOf("http://diagnostics.example.test", "file:///tmp/report", "diagnostics.example.test", "https:///", "https://bad host", "https://host\\@other.test")) {
            assertNull(value, DebugBridgeConfiguration.baseUrl(true, value))
        }
    }

    @Test
    fun embeddedCredentialsOrRequestSuffixesAreNeverAccepted() {
        for (value in listOf("https://owner:password@diagnostics.example.test", "https://diagnostics.example.test/api", "https://diagnostics.example.test//", "https://diagnostics.example.test?token=private", "https://diagnostics.example.test#fragment")) {
            assertNull(value, DebugBridgeConfiguration.baseUrl(true, value))
        }
    }

    @Test
    fun invalidPortsAndOversizedConfigurationAreRejected() {
        for (value in listOf("https://diagnostics.example.test:0", "https://diagnostics.example.test:65536", "https://" + "a".repeat(2049) + ".test")) {
            assertNull(value, DebugBridgeConfiguration.baseUrl(true, value))
        }
    }
}
