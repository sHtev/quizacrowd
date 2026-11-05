package dev.shtev.quizacrowd.quizapi

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/** 32 random bytes, base64url: 43 characters. Shown to the host once; only its hash is stored. */
fun newHostKey(): String = ByteArray(32).also(SecureRandom()::nextBytes).let(Base64.getUrlEncoder().withoutPadding()::encodeToString)

fun sha256(text: String): String =
    MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

/** Constant-time comparison, so response timing doesn't leak how much of a key matched. */
fun sameSecret(a: String, b: String): Boolean = MessageDigest.isEqual(a.toByteArray(), b.toByteArray())
