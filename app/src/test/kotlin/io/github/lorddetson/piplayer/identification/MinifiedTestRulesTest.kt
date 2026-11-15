package io.github.lorddetson.piplayer.identification

import io.github.lorddetson.piplayer.fakes.TestServices
import io.github.lorddetson.piplayer.identification.musicbrainz.RecordingSearchResponse
import java.io.DataInputStream
import java.io.File
import java.lang.reflect.Modifier
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The keep rules of the minified instrumented run (`app/proguard-minified-tests.pro`, TI §11.2) name the app code that
 * only the tests call. Nothing on CI runs the minified build, so these checks keep the hand-written list in step with
 * the code on every JVM run: a renamed or removed entry point, an app type in the signatures of the shared fakes or of
 * [IdentificationServices.create], an app class or member that the fakes' bytecode reaches (a type built only inside a
 * body, a `$default` bridge) or an app type that `MinifiedClientsTest` imports fails here instead of as a missing class
 * or method in the next local minified run. They read names, not what R8 does with them, so they do not replace that
 * run when the services, the fakes or the clients' calls change.
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

    @Test
    fun `every app class and member the shared fakes reach is named`() {
        val fakes = listOf(TestServices::class.java, *TestServices::class.java.declaredClasses)
        val refs = fakes.flatMap { references(it) }.filter { it.owner.startsWith(APP) && !it.owner.startsWith("$APP.fakes.") }
        assertThat(refs.map { it.owner }).describedAs("app classes in the fakes' bytecode")
            .contains(IdentificationServices::class.java.name)

        val notNamed = refs.filterNot { ref ->
            val owning = rules.filter { covers(it, ref.owner) }
            owning.isNotEmpty() && (ref.member == null || owning.any { rule -> rule.members.any { names(it, ref.member) } })
        }.map { ref -> ref.member?.let { "${ref.owner} { $it }" } ?: ref.owner }.distinct()
        assertThat(notNamed).describedAs("app classes and members that TestServices uses, missing from " +
            "proguard-minified-tests.pro").isEmpty()
    }

    @Test
    fun `every app type the minified test imports is kept`() {
        val source = File("src/androidTest/kotlin/io/github/lorddetson/piplayer/identification/MinifiedClientsTest.kt")
        val imports = Regex("""^import\s+($APP\.[\w.]+)""", RegexOption.MULTILINE).findAll(source.readText())
            .map { it.groupValues[1] }.toList()
        assertThat(imports).describedAs("app imports of MinifiedClientsTest").contains(RESULT_TYPE)

        // The fakes are compiled into the test APK, BuildConfig's constants into the test itself; the result type is the
        // one the test expects R8 to rename, kept only through the calls that return it.
        val appTypes = imports.filterNot { it.startsWith("$APP.fakes.") || it == "$APP.BuildConfig" || it == RESULT_TYPE }
        val notKept = appTypes.filterNot { name -> rules.any { covers(it, name) || it.className.startsWith("$name$") } }
        assertThat(notKept).describedAs("app types that MinifiedClientsTest imports, missing from " +
            "proguard-minified-tests.pro").isEmpty()
    }

    private fun load(name: String): Class<*>? =
        try {
            Class.forName(name, false, javaClass.classLoader)
        } catch (_: ClassNotFoundException) {
            null
        }

    private fun exists(type: Class<*>, member: String): Boolean {
        val name = memberName(member)
        return when {
            name == "*" || name == "**" -> true
            name == "<init>" -> type.declaredConstructors.any { Modifier.isPublic(it.modifiers) }
            '(' in member -> type.declaredMethods.any { it.name == name }
            else -> type.declaredFields.any { it.name == name }
        }
    }

    /** The name in a rule's member line: `public ** create(...)` gives `create`, `public *` gives `*`. */
    private fun memberName(member: String): String = member.substringBefore('(').trim().split(Regex("""\s+""")).last()

    private fun covers(rule: Rule, className: String): Boolean =
        rule.className == className ||
            (rule.className.endsWith("**") && className.startsWith(rule.className.removeSuffix("**")))

    private fun names(ruleMember: String, member: String): Boolean =
        memberName(ruleMember).let { it == "*" || it == "**" || it == member }

    /** A class the bytecode of a type uses, with the field or method of it that the bytecode reaches, if any. */
    private data class Reference(val owner: String, val member: String?)

    /** Reads the class, field and method references of [type]'s constant pool (JVMS §4.4). */
    private fun references(type: Class<*>): List<Reference> {
        val resource = type.name.replace('.', '/') + ".class"
        DataInputStream(requireNotNull(type.getResourceAsStream("/$resource")) { resource }).use { input ->
            check(input.readInt() == 0xCAFEBABE.toInt()) { "not a class file: $resource" }
            input.skipBytes(4) // minor and major version
            val count = input.readUnsignedShort()
            val utf8 = arrayOfNulls<String>(count)
            val classes = IntArray(count) { -1 }
            val pairs = arrayOfNulls<Pair<Int, Int>>(count)
            val memberRefs = mutableListOf<Int>()
            var index = 1
            while (index < count) {
                when (val tag = input.readUnsignedByte()) {
                    1 -> utf8[index] = input.readUTF()
                    7 -> classes[index] = input.readUnsignedShort()
                    9, 10, 11, 12 -> {
                        pairs[index] = input.readUnsignedShort() to input.readUnsignedShort()
                        if (tag != 12) memberRefs += index
                    }
                    8, 16, 19, 20 -> input.skipBytes(2)
                    15 -> input.skipBytes(3)
                    3, 4, 17, 18 -> input.skipBytes(4)
                    5, 6 -> input.skipBytes(8).also { index++ } // a long or double takes two entries
                    else -> error("unknown constant pool tag $tag in $resource")
                }
                index++
            }
            fun className(classIndex: Int): String {
                val name = utf8[classes[classIndex]]!!
                val element = if (name.startsWith('[')) name.trimStart('[').removePrefix("L").removeSuffix(";") else name
                return element.replace('/', '.')
            }
            return classes.indices.filter { classes[it] >= 0 }.map { Reference(className(it), null) } +
                memberRefs.map { ref ->
                    val (owner, nameAndType) = pairs[ref]!!
                    Reference(className(owner), utf8[pairs[nameAndType]!!.first])
                }
        }
    }

    private data class Rule(val className: String, val members: List<String>)

    private companion object {
        const val APP = "io.github.lorddetson.piplayer"
        val RESULT_TYPE: String = RecordingSearchResponse::class.java.name
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
