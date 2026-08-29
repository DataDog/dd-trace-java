import datadog.gradle.plugin.testJvmConstraints.TestJvmSpec
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property

/*
 * Applies JMH with defaults from `-PtestJvm` and `-Pjmh.*`. Modules can override them in their
 * `jmh {}` block.
 */
// This plugin is produced by the same buildSrc build, so it cannot be resolved from this
// precompiled script's `plugins {}` block. Apply it by ID once both plugins are available at runtime.
pluginManager.apply("dd-trace-java.test-jvm-constraints")

// JMH is versioned in the root build with `apply false`, not added to buildSrc's implementation
// classpath. Applying it by ID here reuses the consuming build's plugin classpath.
pluginManager.apply("me.champeau.jmh")

val testJvmSpec = TestJvmSpec(project)
val jmh = extensions.getByName("jmh")


jmhProperty<String>("getJvm").convention(testJvmSpec.javaTestLauncher.map { it.executablePath.asFile.absolutePath })
providers.gradleProperty("jmh.includes").map(::commaSeparated).let {
  if (it.isPresent) {
    jmhListProperty("getIncludes").convention(it.map { includes -> listOf(includes.joinToString("|")) })
  }
}
providers.gradleProperty("jmh.profilers").map(::commaSeparated).let {
  if (it.isPresent) {
    jmhListProperty("getProfilers").convention(it)
  }
}
providers.gradleProperty("jmh.forks").map(String::toInt).let {
  if (it.isPresent) {
    jmhProperty<Int>("getFork").convention(it)
  }
}
providers.gradleProperty("jmh.threads").map(String::toInt).let {
  if (it.isPresent) {
    jmhProperty<Int>("getThreads").convention(it)
  }
}

// JMH types are not on buildSrc's compile classpath, so access its extension through Gradle's public
// property types.
@Suppress("UNCHECKED_CAST")
fun <T : Any> jmhProperty(getterName: String): Property<T> =
  jmh.javaClass.getMethod(getterName).invoke(jmh) as Property<T>

@Suppress("UNCHECKED_CAST")
fun jmhListProperty(getterName: String): ListProperty<String> =
  jmh.javaClass.getMethod(getterName).invoke(jmh) as ListProperty<String>

fun commaSeparated(value: String): List<String> =
  value.split(",").map(String::trim).filter(String::isNotEmpty)
