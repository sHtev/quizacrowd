package dev.shtev.quizacrowd.quizapi

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter

object Qr {
    /** The page a player's phone opens; plain http only for local development. */
    fun playUrl(tenant: String, quizId: String, baseDomain: String): String {
        val scheme = if (baseDomain == "localhost") "http" else "https"
        return "$scheme://$tenant.$baseDomain/play?quiz=$quizId"
    }

    /** The QR code as an SVG: one 1x1 square per dark module, so it scales cleanly on a big screen. */
    fun svg(text: String): String {
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, mapOf(EncodeHintType.MARGIN to 2))
        val squares = buildString {
            for (y in 0 until matrix.height) for (x in 0 until matrix.width) {
                if (matrix[x, y]) append("M$x ${y}h1v1h-1z")
            }
        }
        return """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ${matrix.width} ${matrix.height}" shape-rendering="crispEdges">""" +
            """<rect width="100%" height="100%" fill="#fff"/><path d="$squares" fill="#000"/></svg>"""
    }
}
