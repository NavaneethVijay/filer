package com.navaneeth.filer.data.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * files.db is migrated, never rebuilt (it points at files in the bin and Locked folder). The 2→3
 * migration must create the plugin tables exactly as Room expects, or the app fails to open it.
 */
class FilesDbMigrationTest {

    /** Every CREATE statement in Room's exported schema, with the table name filled in. */
    private fun schemaSql(version: Int): List<String> {
        val file = File("schemas/com.navaneeth.filer.data.files.FileManagerDatabase/$version.json")
        assertTrue("Room schema $version.json missing (build once to export it)", file.exists())
        val token = Regex("\"tableName\": \"([^\"]+)\"|\"createSql\": \"((?:[^\"\\\\]|\\\\.)*)\"")
        var table = ""
        val statements = ArrayList<String>()
        for (match in token.findAll(file.readText())) {
            val name = match.groupValues[1]
            if (name.isNotEmpty()) {
                table = name
            } else {
                statements += match.groupValues[2].replace("\\\"", "\"").replace("\${TABLE_NAME}", table)
            }
        }
        return statements
    }

    @Test fun pluginTablesMatchRoomsSchema() {
        val pluginTables = listOf("file_text", "file_tags", "rename_suggestions", "file_ai_cache")
        val expected = schemaSql(3).filter { sql -> pluginTables.any { sql.contains("`$it` (") || sql.contains("ON `$it`") } }
        assertEquals(6, expected.size)
        assertEquals(expected.sorted(), FileManagerDatabase.PLUGIN_TABLES_SQL.sorted())
    }
}
