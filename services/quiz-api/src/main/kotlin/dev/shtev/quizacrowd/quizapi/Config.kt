package dev.shtev.quizacrowd.quizapi

data class Config(
    val baseDomain: String,
    val databaseUrl: String,
    val databaseUser: String,
    val databasePassword: String,
    val valkeyUrl: String,
    val adminToken: String,
    val joinTokenPrivateKeyPem: String,
    val questionSeconds: Int = 20,
) {
    companion object {
        fun fromEnv(env: Map<String, String> = System.getenv()): Config {
            fun required(name: String) = env[name] ?: error("missing environment variable $name")
            return Config(
                baseDomain = required("BASE_DOMAIN"),
                databaseUrl = required("DATABASE_URL"),
                databaseUser = required("DATABASE_USER"),
                databasePassword = required("DATABASE_PASSWORD"),
                valkeyUrl = required("VALKEY_URL"),
                adminToken = required("ADMIN_TOKEN"),
                joinTokenPrivateKeyPem = required("JOIN_TOKEN_PRIVATE_KEY"),
                questionSeconds = env["QUESTION_SECONDS"]?.toInt() ?: 20,
            )
        }
    }
}
