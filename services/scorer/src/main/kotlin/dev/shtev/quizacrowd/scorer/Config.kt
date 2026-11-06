package dev.shtev.quizacrowd.scorer

data class Config(
    val databaseUrl: String,
    val databaseUser: String,
    val databasePassword: String,
    val valkeyUrl: String,
    val kafkaBootstrap: String,
    val groupId: String = "scorer",
    val publishIntervalMs: Long = 500,
) {
    companion object {
        fun fromEnv(env: Map<String, String> = System.getenv()): Config {
            fun required(name: String) = env[name] ?: error("missing environment variable $name")
            return Config(
                databaseUrl = required("DATABASE_URL"),
                databaseUser = required("DATABASE_USER"),
                databasePassword = required("DATABASE_PASSWORD"),
                valkeyUrl = required("VALKEY_URL"),
                kafkaBootstrap = required("KAFKA_BOOTSTRAP"),
            )
        }
    }
}
