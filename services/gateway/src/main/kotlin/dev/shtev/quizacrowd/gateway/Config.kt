package dev.shtev.quizacrowd.gateway

data class Config(
    val baseDomain: String,
    val valkeyUrl: String,
    val joinTokenPublicKeyPem: String,
) {
    companion object {
        fun fromEnv(env: Map<String, String> = System.getenv()): Config {
            fun required(name: String) = env[name] ?: error("missing environment variable $name")
            return Config(
                baseDomain = required("BASE_DOMAIN"),
                valkeyUrl = required("VALKEY_URL"),
                joinTokenPublicKeyPem = required("JOIN_TOKEN_PUBLIC_KEY"),
            )
        }
    }
}
