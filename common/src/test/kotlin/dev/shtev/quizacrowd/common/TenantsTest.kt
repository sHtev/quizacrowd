package dev.shtev.quizacrowd.common

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TenantsTest {
    @Test
    fun subdomainIsTheTenant() {
        assertEquals("redlion", tenantFromHost("redlion.quizacrowd.app", "quizacrowd.app"))
    }

    @Test
    fun portIsIgnored() {
        assertEquals("redlion", tenantFromHost("redlion.localhost:8080", "localhost"))
    }

    @Test
    fun apexHasNoTenant() {
        assertNull(tenantFromHost("quizacrowd.app", "quizacrowd.app"))
    }

    @Test
    fun nestedSubdomainRejected() {
        assertNull(tenantFromHost("a.b.quizacrowd.app", "quizacrowd.app"))
    }

    @Test
    fun otherDomainRejected() {
        assertNull(tenantFromHost("redlion.example.com", "quizacrowd.app"))
    }

    @Test
    fun tenantIsLowercased() {
        assertEquals("redlion", tenantFromHost("RedLion.quizacrowd.app", "quizacrowd.app"))
    }

    @Test
    fun invalidSlugsRejected() {
        for (host in listOf(
            "-pub.quizacrowd.app",
            "pub-.quizacrowd.app",
            "red_lion.quizacrowd.app"
        )) {
            assertNull(tenantFromHost(host, "quizacrowd.app"), host)
        }
    }
}
