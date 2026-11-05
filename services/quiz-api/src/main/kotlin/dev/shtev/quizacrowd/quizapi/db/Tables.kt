package dev.shtev.quizacrowd.quizapi.db

import dev.shtev.quizacrowd.common.quizJson
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.javatime.timestampWithTimeZone
import org.jetbrains.exposed.v1.json.jsonb

// Exposed mappings for the tables in db/migration/V1__schema.sql (Flyway owns the schema).

object Tenants : Table("tenants") {
    val slug = text("slug")
    val name = text("name")
    val hostKeyHash = text("host_key_hash")
    override val primaryKey = PrimaryKey(slug)
}

object Quizzes : Table("quizzes") {
    val id = javaUUID("id")
    val tenantSlug = text("tenant_slug")
    val name = text("name")
    val mode = text("mode")
    val state = text("state")
    val currentIndex = integer("current_index").nullable()
    override val primaryKey = PrimaryKey(id)
}

object Questions : Table("questions") {
    val id = javaUUID("id")
    val quizId = javaUUID("quiz_id")
    val idx = integer("idx")
    val text = text("text")
    val answerOptions = jsonb<List<String>>("options", quizJson) // "options" clashes with Table.options
    val correctIndex = integer("correct_index")
    val startedAt = timestampWithTimeZone("started_at").nullable()
    val deadline = timestampWithTimeZone("deadline").nullable()
    override val primaryKey = PrimaryKey(id)
}

object Players : Table("players") {
    val id = javaUUID("id")
    val quizId = javaUUID("quiz_id")
    val name = text("name")
    override val primaryKey = PrimaryKey(id)
}
