package com.starglean.app.core.network

import java.io.EOFException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class TransportFailureMessageTest {
    @Test fun `known network failures are classified through safe cause chains`() {
        assertEquals(TransportFailureKind.Dns, transportFailureKind(Exception("wrapper", UnknownHostException("private-host"))))
        assertEquals(TransportFailureKind.Timeout, transportFailureKind(SocketTimeoutException("private-url")))
        assertEquals(TransportFailureKind.Tls, transportFailureKind(SSLHandshakeException("private-certificate")))
        assertEquals(TransportFailureKind.Disconnected, transportFailureKind(EOFException("private-response")))
        assertEquals(TransportFailureKind.Unknown, transportFailureKind(IllegalStateException("private-detail")))
    }

    @Test fun `messages never echo exception details and preserve ambiguous delivery`() {
        val marker = "SECRET_URL_TOKEN_COORDINATE"
        TransportFailureKind.entries.forEach { kind ->
            val cause = when (kind) {
                TransportFailureKind.Dns -> UnknownHostException(marker)
                TransportFailureKind.Timeout -> SocketTimeoutException(marker)
                TransportFailureKind.Tls -> SSLHandshakeException(marker)
                TransportFailureKind.Disconnected -> EOFException(marker)
                TransportFailureKind.Unknown -> IllegalStateException(marker)
            }
            assertFalse(transportFailureMessage(cause).contains(marker))
        }
        assertEquals(
            "The connection ended before confirmation. The result may still have reached the service.",
            transportFailureMessage(EOFException(marker)),
        )
        assertEquals(
            "A connection error occurred. The result could not be determined.",
            transportFailureMessage(IllegalStateException(marker)),
        )
    }
}
