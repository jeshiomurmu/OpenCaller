package io.github.jeshiomurmu.opencaller.server.db

import io.github.jeshiomurmu.opencaller.server.testing.TestInfra
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/** V1__init.sql is exactly docs/architecture.md §7, and the Exposed tables mirror the migrated database. */
class ServerSchemaTest {
    @Test
    fun migrationIsTheSqlFromArchitectureSection7() {
        val doc = File("../../docs/architecture.md").readText()
        val section = doc.substring(doc.indexOf("## 7. Server data model"))
        val start = section.indexOf("```sql\n") + "```sql\n".length
        val documented = section.substring(start, section.indexOf("```", start))
        val migration = javaClass.getResource("/db/migration/V1__init.sql")!!.readText()
        assertEquals(documented, migration.substringAfter("\n"))
    }

    @Test
    fun exposedTablesMatchTheMigratedDatabase() =
        runBlocking {
            val columns =
                TestInfra.database.query {
                    exec(
                        "SELECT table_name, column_name, is_nullable FROM information_schema.columns " +
                            "WHERE table_schema = 'public' AND table_name <> 'flyway_schema_history'",
                    ) { rs ->
                        buildList { while (rs.next()) add(Triple(rs.getString(1), rs.getString(2), rs.getString(3) == "YES")) }
                    }!!
                }
            val database = columns.groupBy({ it.first }, { it.second to it.third }).mapValues { it.value.toMap() }
            val exposed = allTables.associate { table -> table.tableName to table.columns.associate { it.name to it.columnType.nullable } }
            assertEquals(database.keys.sorted(), exposed.keys.sorted(), "tables")
            exposed.forEach { (table, cols) -> assertEquals(database.getValue(table), cols, "columns of $table (name to nullable)") }
        }

    @Test
    fun enumTypesHaveTheDocumentedLabels() =
        runBlocking {
            val labels =
                TestInfra.database
                    .query {
                        exec(
                            "SELECT t.typname, e.enumlabel FROM pg_type t JOIN pg_enum e ON e.enumtypid = t.oid " +
                                "ORDER BY t.typname, e.enumsortorder",
                        ) { rs -> buildList { while (rs.next()) add(rs.getString(1) to rs.getString(2)) } }!!
                    }.groupBy({ it.first }, { it.second })
            assertEquals(Visibility.entries.map { it.sqlValue }, labels["visibility"])
            assertEquals(ReportLabel.entries.map { it.sqlValue }, labels["report_label"])
            assertEquals(ReviewStatus.entries.map { it.sqlValue }, labels["review_status"])
        }
}
