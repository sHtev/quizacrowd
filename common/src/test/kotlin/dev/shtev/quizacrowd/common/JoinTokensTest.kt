package dev.shtev.quizacrowd.common

import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.minutes

class JoinTokensTest {
    private val claims = JoinClaims(playerId = "p1", quizId = "q1", tenant = "redlion", name = "Ada")
    private val keys = newKeyPair()
    private val start = Instant.parse("2026-11-04T19:00:00Z")

    private fun newKeyPair(): KeyPair =
        KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()

    private fun pem(type: String, der: ByteArray) =
        "-----BEGIN $type-----\n" + Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(der) + "\n-----END $type-----\n"

    private fun privatePem(k: KeyPair) = pem("PRIVATE KEY", k.private.encoded)
    private fun publicPem(k: KeyPair) = pem("PUBLIC KEY", k.public.encoded)
    private fun clockAt(instant: Instant): Clock = Clock.fixed(instant, ZoneOffset.UTC)

    @Test
    fun issuedTokenVerifies() {
        val token = JoinTokenIssuer(privatePem(keys)).issue(claims)
        assertEquals(claims, JoinTokenVerifier(publicPem(keys)).verify(token))
    }

    @Test
    fun tamperedTokenRejected() {
        val token = JoinTokenIssuer(privatePem(keys)).issue(claims)
        val (header, payload, signature) = token.split(".")
        val forged = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(String(Base64.getUrlDecoder().decode(payload)).replace("Ada", "Eve").toByteArray())
        assertNull(JoinTokenVerifier(publicPem(keys)).verify("$header.$forged.$signature"))
    }

    @Test
    fun expiredTokenRejected() {
        val token = JoinTokenIssuer(privatePem(keys), clockAt(start)).issue(claims, ttl = 1.minutes)
        assertNull(JoinTokenVerifier(publicPem(keys), clockAt(start.plusSeconds(120))).verify(token))
    }

    @Test
    fun otherKeyRejected() {
        val token = JoinTokenIssuer(privatePem(newKeyPair())).issue(claims)
        assertNull(JoinTokenVerifier(publicPem(keys)).verify(token))
    }

    @Test
    fun garbageRejected() {
        assertNull(JoinTokenVerifier(publicPem(keys)).verify("not.a.token"))
    }
}
