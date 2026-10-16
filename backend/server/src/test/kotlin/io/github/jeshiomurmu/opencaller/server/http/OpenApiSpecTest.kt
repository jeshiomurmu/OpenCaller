package io.github.jeshiomurmu.opencaller.server.http

import io.github.jeshiomurmu.opencaller.server.testing.apiTest
import io.ktor.server.application.plugin
import io.ktor.server.routing.HttpMethodRouteSelector
import io.ktor.server.routing.RoutingNode
import io.ktor.server.routing.RoutingRoot
import io.ktor.server.routing.getAllRoutes
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** openapi.yaml (docs/architecture.md §8) describes every route the server registers and every problem code it sends. */
class OpenApiSpecTest {
    private val spec = javaClass.getResource("/openapi.yaml")!!.readText()

    // "  /path:" followed by "    get:" / "    post:" lines, as written in openapi.yaml.
    private val documented: Set<Pair<String, String>> =
        buildSet {
            var path: String? = null
            spec.lines().forEach { line ->
                Regex("""^ {2}(/\S*):$""").find(line)?.let { path = it.groupValues[1] }
                Regex("""^ {4}(get|post|put|patch|delete):$""").find(line)?.let { match ->
                    path?.let {
                        add(it to match.groupValues[1].uppercase())
                    }
                }
                if (line.isNotBlank() && !line.startsWith(" ")) path = null
            }
        }

    @Test
    fun everyRouteIsDocumented() =
        apiTest {
            var routes: List<Pair<String, String>> = emptyList()
            application {
                routes =
                    plugin(RoutingRoot).getAllRoutes().mapNotNull { route ->
                        val method = (route.selector as? HttpMethodRouteSelector)?.method ?: return@mapNotNull null
                        pathOf(route) to method.value
                    }
            }
            startApplication()
            assertTrue(routes.isNotEmpty())
            routes.forEach { route -> assertTrue(route in documented, "$route is not in openapi.yaml (documented: $documented)") }
            assertEquals(routes.toSet(), documented, "openapi.yaml documents routes the server doesn't have")
        }

    @Test
    fun everyProblemCodeIsDocumented() {
        val used =
            File("src/main/kotlin")
                .walkTopDown()
                .filter { it.extension == "kt" }
                .flatMap { file ->
                    Regex("""ApiProblem\(\s*HttpStatusCode\.\w+,\s*"([a-z_]+)"""").findAll(file.readText()).map { it.groupValues[1] }
                }.toSet()
        val enum =
            spec
                .substringAfter("    Problem:")
                .substringAfter("enum:")
                .lines()
                .drop(1)
                .takeWhile {
                    it.trim().startsWith("- ")
                }.map { it.trim().removePrefix("- ") }
        assertTrue(used.size >= 10, "found only $used")
        used.forEach { code -> assertTrue(code in enum, "problem code $code is missing from openapi.yaml") }
    }

    // Ktor prints a route as its full path plus selectors, e.g. "/v1/auth/start/(method:POST)".
    private fun pathOf(route: RoutingNode): String = route.toString().substringBefore("/(method:").ifEmpty { "/" }
}
