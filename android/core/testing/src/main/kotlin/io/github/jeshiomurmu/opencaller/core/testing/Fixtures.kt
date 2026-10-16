package io.github.jeshiomurmu.opencaller.core.testing

/**
 * Reads the shared test fixtures in `core/testing/src/main/resources/fixtures/` (docs/testing.md §3).
 * Lines starting with `#` and blank lines are ignored.
 */
object Fixtures {
    /** Returns the data lines of fixture [name] (e.g. `numbers.csv`). */
    fun lines(name: String): List<String> {
        val stream =
            checkNotNull(Fixtures::class.java.getResourceAsStream("/fixtures/$name")) {
                "Fixture $name not found"
            }
        return stream.bufferedReader(Charsets.UTF_8).use { reader ->
            reader.readLines().map(String::trim).filter { it.isNotEmpty() && !it.startsWith("#") }
        }
    }

    /** Returns the rows of CSV fixture [name] split on commas (fixtures contain no quoted fields). */
    fun csv(name: String): List<List<String>> = lines(name).map { line -> line.split(',').map(String::trim) }
}
