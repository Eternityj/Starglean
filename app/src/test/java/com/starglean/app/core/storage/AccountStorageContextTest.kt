package com.starglean.app.core.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AccountStorageContextTest {
    @Test fun `account identity is canonicalized without accepting path-like values`() {
        assertEquals("11111111-1111-4111-8111-111111111111",
            normalizeAccountId("11111111-1111-4111-8111-111111111111"))
        assertThrows(AccountLibraryException::class.java) { normalizeAccountId("../11111111-1111-4111-8111-111111111111") }
        assertThrows(AccountLibraryException::class.java) { normalizeAccountId("not-an-account") }
    }
}
