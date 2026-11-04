package dev.shtev.quizacrowd.common

import com.auth0.jwt.JWT
import com.auth0.jwt.JWTVerifier
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.exceptions.JWTVerificationException
import java.security.KeyFactory
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.time.Clock
import java.util.Base64
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.toJavaDuration

/** Who a join token belongs to. The player ID is the token's subject. */
data class JoinClaims(val playerId: String, val quizId: String, val tenant: String, val name: String)

/**
 * Signs join tokens with an ES256 private key (quiz-api only). Services that only check tokens,
 * and later the edge worker, hold just the public key.
 */
class JoinTokenIssuer(privateKeyPem: String, private val clock: Clock = Clock.systemUTC()) {
    private val algorithm = Algorithm.ECDSA256(null, parsePrivateKey(privateKeyPem))

    fun issue(claims: JoinClaims, ttl: Duration = 12.hours): String {
        val now = clock.instant()
        return JWT.create()
            .withSubject(claims.playerId)
            .withClaim("quiz", claims.quizId)
            .withClaim("tenant", claims.tenant)
            .withClaim("name", claims.name)
            .withIssuedAt(now)
            .withExpiresAt(now.plus(ttl.toJavaDuration()))
            .sign(algorithm)
    }
}

class JoinTokenVerifier(publicKeyPem: String, clock: Clock = Clock.systemUTC()) {
    private val verifier = (JWT.require(Algorithm.ECDSA256(parsePublicKey(publicKeyPem), null))
        as JWTVerifier.BaseVerification).build(clock)

    /** The token's claims, or null if it is malformed, expired, or not signed by our key. */
    fun verify(token: String): JoinClaims? {
        val jwt = try {
            verifier.verify(token)
        } catch (e: JWTVerificationException) {
            return null
        }
        return JoinClaims(
            playerId = jwt.subject ?: return null,
            quizId = jwt.getClaim("quiz").asString() ?: return null,
            tenant = jwt.getClaim("tenant").asString() ?: return null,
            name = jwt.getClaim("name").asString() ?: return null,
        )
    }
}

private fun pemBody(pem: String): ByteArray =
    Base64.getMimeDecoder().decode(pem.lines().filterNot { it.startsWith("-----") }.joinToString(""))

private fun parsePrivateKey(pem: String) =
    KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(pemBody(pem))) as ECPrivateKey

private fun parsePublicKey(pem: String) =
    KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(pemBody(pem))) as ECPublicKey
