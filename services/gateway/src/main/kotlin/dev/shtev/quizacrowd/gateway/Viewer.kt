package dev.shtev.quizacrowd.gateway

import kotlin.uuid.Uuid
import java.util.UUID
import dev.shtev.quizacrowd.common.JoinClaims

/** Who is on the other end of a WebSocket. */
sealed interface Viewer {
    val quizId: String

    data class Player(val playerId: String, override val quizId: String) : Viewer
    data class Screen(override val quizId: String) : Viewer
}

/**
 * Who a /ws connection is for, or null to refuse it. [hostTenant] is the tenant from the Host header.
 *
 * - A player brings a join [token]; [verify] gives its claims, or null if it's invalid. The token's
 *   tenant must match [hostTenant].
 * - The big screen brings only the quiz ID ([screenQuizId]), which must be a UUID. It sees only
 *   what's on the pub's screen anyway.
 * - A token, valid or not, makes it a player connection: a bad token is refused, never treated as a screen.
 */
fun viewerFor(token: String?, screenQuizId: String?, hostTenant: String?, verify: (String) -> JoinClaims?): Viewer? {
    return hostTenant?.let {
        token?.let {
            verify(token)?.takeIf { it.tenant == hostTenant }
                ?.let { Viewer.Player(it.playerId, it.quizId) }
                ?: return null
        } ?: screenQuizId?.let {
            runCatching { UUID.fromString(screenQuizId) }.getOrNull()
                ?.let { Viewer.Screen(screenQuizId) }
        }
    }
}
