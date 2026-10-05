package com.starglean.app.core.network

import java.io.EOFException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.PortUnreachableException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.Collections
import java.util.IdentityHashMap
import javax.net.ssl.SSLException

internal enum class TransportFailureKind { Dns, Timeout, Tls, Disconnected, Unknown }

internal fun transportFailureKind(error: Exception): TransportFailureKind {
    val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
    var current: Throwable? = error
    while (current != null && seen.add(current)) {
        when (current) {
            is UnknownHostException -> return TransportFailureKind.Dns
            is SocketTimeoutException, is InterruptedIOException -> return TransportFailureKind.Timeout
            is SSLException -> return TransportFailureKind.Tls
            is ConnectException, is NoRouteToHostException, is PortUnreachableException,
            is SocketException, is EOFException -> return TransportFailureKind.Disconnected
        }
        current = current.cause
    }
    return TransportFailureKind.Unknown
}

internal fun transportFailureMessage(error: Exception): String = when (transportFailureKind(error)) {
    TransportFailureKind.Dns -> "The service address could not be reached. No result was confirmed."
    TransportFailureKind.Timeout -> "The service did not respond in time. The result is not yet known."
    TransportFailureKind.Tls -> "A secure connection could not be established. No result was confirmed."
    TransportFailureKind.Disconnected -> "The connection ended before confirmation. The result may still have reached the service."
    TransportFailureKind.Unknown -> "A connection error occurred. The result could not be determined."
}
