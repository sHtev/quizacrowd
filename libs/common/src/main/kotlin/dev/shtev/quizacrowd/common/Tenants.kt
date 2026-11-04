package dev.shtev.quizacrowd.common

private val SLUG = Regex("""^[a-z0-9]([a-z0-9-]{0,30}[a-z0-9])?$""")

fun tenantFromHost(host: String, baseDomain: String): String? {
    val hostname = host.substringBefore(':')
    val domainName = baseDomain.lowercase()
    if (!hostname.endsWith(".$domainName")) return null
    val tenant = hostname.removeSuffix(".$domainName").lowercase()
    return tenant.takeIf { SLUG.matches(it) }
}
