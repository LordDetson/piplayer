package io.github.lorddetson.piplayer.identification

import io.github.lorddetson.piplayer.fakes.TestServices
import java.io.File
import java.lang.reflect.Modifier
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The keep rules of the minified instrumented run (`app/proguard-minified-tests.pro`, TI §11.2) name the app code that
 * only the tests call. Nothing on CI runs the minified build, so these checks keep the hand-written list in step with
 * the code on every JVM run: a renamed or removed entry point, or a new app type that the shared fakes pass to
 * [IdentificationServices.create], fails here instead of as a missing class in the next local minified run. They do
 * not replace that run when the services, the fakes or the clients' calls change.
 */
class MinifiedTestRulesTest {

    private val rules = parse(File("proguard-minified-tests.pro").readText())

    @Test
    fun `every app class and member the rules name exists`() {
        val appRules = rules.filter { it.className.startsWith(APP) }
        assertThat(appRules).isNotEmpty()
        val missing = appRules.flatMap { rule ->
            if (rule.className.endsWith("**")) {
                // A prefix: at least the class without the wildcard, or one nested in it, exists.
                val prefix = rule.className.removeSuffix("**")
                return@flatMap if (load(prefix) == null) listOf(rule.className) else emptyList()
            }
            val type = load(rule.className) ?: return@flatMap listOf(rule.className)
            rule.members.mapNotNull { member -> member.takeUnless { exists(type, it) }?.let { "${rule.className} { $it }" } }
        }
        assertThat(missing).describedAs("named in proguard-minified-tests.pro but not in the code").isEmpty()
    }

    @Test
    fun `every app type the shared fakes pass to the services is kept`() {
        val create = IdentificationServices.Companion::class.java.methods.filter { it.name.startsWith("create") }
        assertThat(create).describedAs("IdentificationServices.create").isNotEmpty()
        val fakes = listOf(TestServices::class.java, *TestServices::class.java.declaredClasses)
        val signatures = create.flatMap { it.parameterTypes.toList() } +
            fakes.flatMap { fake ->
                fake.methods.flatMap { it.parameterTypes.toList() + it.returnType } +
                    fake.constructors.flatMap { it.parameterTypes.toList() }
            }
        val appTypes = signatures.map { it.name }.filter { it.startsWith(APP) && !it.startsWith("$APP.fakes.") }.toSet()
        assertThat(appTypes).describedAs("app types in the signatures").contains(IdentificationServices::class.java.name)

        val kept = rules.map { it.className }
        val notKept = appTypes.filterNot { name ->
            kept.any { it == name || (it.endsWith("**") && name.startsWith(it.removeSuffix("**"))) }
        }
        assertThat(notKept).describedAs("app types that TestServices or IdentificationServices.create take or return, " +
            "missing from proguard-minified-tests.pro").isEmpty()
    }

    private fun load(name: String): Class<*>? =
        try {
            Class.forName(name, false, javaClass.classLoader)
        } catch (_: ClassNotFoundException) {
            null
        }

    private fun exists(type: Class<*>, member: String): Boolean {
        val name = member.substringBefore('(').trim().split(Regex("\\s+")).last()
        return when {
            name == "*" || name == "**" -> true
            name == "<init>" -> type.declaredConstructors.any { Modifier.isPublic(it.modifiers) }
            '(' in member -> type.declaredMethods.any { it.name == name }
            else -> type.declaredFields.any { it.name == name }
        }
    }

    private data class Rule(val className: String, val members: List<String>)

    private companion object {
        const val APP = "io.github.lorddetson.piplayer"
        val RULE = Regex("""-keep\w*(?:,\w+)*\s+(?:class|enum|interface)\s+(\S+)\s*(?:\{([^}]*)\})?""")

        fun parse(text: String): List<Rule> {
            val code = text.lines().joinToString("\n") { it.substringBefore('#') }
            return RULE.findAll(code).map { match ->
                val members = match.groupValues[2].split(';').map { it.trim() }.filter { it.isNotEmpty() }
                Rule(match.groupValues[1], members)
            }.toList()
        }
    }
}
