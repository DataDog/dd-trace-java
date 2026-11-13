package datadog.gradle.plugin.csi

import datadog.gradle.plugin.GradleFixture
import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.File
import java.nio.file.Files
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import javax.tools.ToolProvider

class CallSiteInstrumentationPluginTest : GradleFixture() {
  private val buildGradle = """
    plugins {
      id("java")
      id("dd-trace-java.call-site-instrumentation")
    }

    java {
      sourceCompatibility = JavaVersion.VERSION_1_8
      targetCompatibility = JavaVersion.VERSION_1_8
    }

    csi {
      suffix.set("CallSite")
      targetFolder.set(project.layout.buildDirectory.dir("csi"))
    }

    repositories {
      mavenCentral()
    }

    dependencies {
      implementation("net.bytebuddy:byte-buddy:1.18.12")
      implementation("com.google.auto.service:auto-service-annotations:1.1.1")
    }
  """

  private val dependencyAdvice = """
    import datadog.trace.agent.tooling.csi.CallSite;
    import datadog.trace.agent.tooling.csi.CallSites;

    @CallSite(spi = CallSites.class)
    public class BeforeAdviceCallSite {
      @CallSite.Before("void example.Target.call(java.lang.String)")
      public static void beforeCall(@CallSite.Argument final String value) {
      }
    }
  """

  @Test
  fun `test call site instrumentation plugin`() {
    createGradleProject(
      buildGradle,
      """
       import datadog.trace.agent.tooling.csi.*;

       @CallSite(spi = CallSites.class)
       public class BeforeAdviceCallSite {
         @CallSite.Before("java.lang.StringBuilder java.lang.StringBuilder.append(java.lang.String)")
         public static void beforeAppend(@CallSite.This final StringBuilder self, @CallSite.Argument final String param) {
         }
       }
      """
    )

    val result = buildGradleProject()

    val generated = buildFile("csi/BeforeAdviceCallSites.java")
    assertTrue(generated.exists())

    val output = result.output
    assertFalse(output.contains("[⨉]"))
    assertTrue(output.contains("[✓] @CallSite BeforeAdviceCallSite"))
  }

  @Test
  fun `test call site instrumentation plugin with error`() {
    createGradleProject(
      buildGradle,
      """
       import datadog.trace.agent.tooling.csi.*;

       @CallSite(spi = CallSites.class)
       public class BeforeAdviceCallSite {
         @CallSite.Before("java.lang.StringBuilder java.lang.StringBuilder.append(java.lang.String)")
         private void beforeAppend(@CallSite.This final StringBuilder self, @CallSite.Argument final String param) {
         }
       }
      """
    )

    val result = run("build", "--info", "--stacktrace", forwardOutput = true, expectFailure = true)

    val generated = buildFile("csi/BeforeAdviceCallSites.java")
    assertFalse(generated.exists())

    val output = result.output
    assertFalse(output.contains("[✓]"))
    assertTrue(output.contains("ADVICE_METHOD_NOT_STATIC_AND_PUBLIC"))
  }

  @ParameterizedTest
  @ValueSource(strings = ["runtimeOnly", "additionalPaths"])
  fun `dependency changes invalidate call site generation`(dependencySource: String) {
    val dependencyConfiguration = if (dependencySource == "runtimeOnly") {
      """dependencies { runtimeOnly(files("dependency.jar")) }"""
    } else {
      """csi { additionalPaths.from(files("dependency.jar")) }"""
    }
    createGradleProject(
      buildGradle + "\n" + dependencyConfiguration,
      dependencyAdvice
    )
    writeJavaSource(
      "GeneratedCallSiteUsage",
      "class GeneratedCallSiteUsage { BeforeAdviceCallSites callSites; }",
      sourceSet = "test"
    )
    writeDependencyJar("public static void call(String value) {}")

    val first = buildGradleProject()
    assertEquals(TaskOutcome.SUCCESS, first.task(":generateCallSiteJava")?.outcome, first.output)
    assertTrue(buildFile("csi/BeforeAdviceCallSites.java").exists())
    assertEquals(TaskOutcome.SUCCESS, first.task(":compileCsiJava")?.outcome, first.output)
    assertEquals(TaskOutcome.SUCCESS, first.task(":compileTestJava")?.outcome, first.output)

    val unchanged = run("generateCallSiteJava", "--info", "--stacktrace")
    assertEquals(TaskOutcome.UP_TO_DATE, unchanged.task(":generateCallSiteJava")?.outcome, unchanged.output)

    writeDependencyJar("")

    val changed = run("generateCallSiteJava", "--info", "--stacktrace", expectFailure = true)
    assertEquals(TaskOutcome.UP_TO_DATE, changed.task(":compileJava")?.outcome, changed.output)
    assertEquals(TaskOutcome.FAILED, changed.task(":generateCallSiteJava")?.outcome, changed.output)
    assertTrue(changed.output.contains("UNRESOLVED_METHOD"), changed.output)
  }

  @Test
  fun `analysis classpath builds runtime project dependencies`() {
    createGradleProject(
      buildGradle + "\n" + """dependencies { runtimeOnly(project(":dependency")) }""",
      dependencyAdvice
    )
    addSubproject(
      "dependency",
      """
        plugins { java }
        java {
          sourceCompatibility = JavaVersion.VERSION_1_8
          targetCompatibility = JavaVersion.VERSION_1_8
        }
      """
    )
    writeJavaSource(
      "example.Target",
      "package example; public class Target { public static void call(String value) {} }",
      projectPath = "dependency"
    )

    val result = run("generateCallSiteJava", "--info", "--stacktrace")

    assertEquals(TaskOutcome.SUCCESS, result.task(":dependency:jar")?.outcome, result.output)
    assertEquals(TaskOutcome.SUCCESS, result.task(":generateCallSiteJava")?.outcome, result.output)
    assertTrue(buildFile("csi/BeforeAdviceCallSites.java").exists())
  }

  private fun writeDependencyJar(method: String) {
    val source = writeFile("dependency-src/example/Target.java", "package example; public class Target { $method }")
    val classes = dir("dependency-classes")
    assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "-d", classes.path, source.path))
    JarOutputStream(file("dependency.jar").outputStream()).use { jar ->
      jar.putNextEntry(JarEntry("example/Target.class"))
      jar.write(File(classes, "example/Target.class").readBytes())
      jar.closeEntry()
    }
  }

  private fun createGradleProject(gradleFile: String, advice: String) {
    val projectFolder = File(System.getProperty("user.dir")).parentFile
    val callSiteJar = File(projectFolder, "buildSrc/call-site-instrumentation-plugin/build/libs/call-site-instrumentation-plugin-all.jar")
    val testCallSiteJarDir = dir("buildSrc/call-site-instrumentation-plugin/build/libs")

    Files.copy(
      callSiteJar.toPath(),
      testCallSiteJarDir.toPath().resolve(callSiteJar.name)
    )

    writeRootProject(gradleFile)

    val advicePackage = parsePackage(advice)
    val adviceClassName = parseClassName(advice)
    val adviceSourceName = if (advicePackage.isEmpty()) {
      adviceClassName
    } else {
      "$advicePackage.$adviceClassName"
    }
    writeJavaSource(adviceSourceName, advice)

    val csiSource = File(projectFolder, "dd-java-agent/agent-tooling/src/main/java/datadog/trace/agent/tooling/csi")
    csiSource.listFiles()?.forEach { src ->
      writeJavaSource("datadog.trace.agent.tooling.csi.${src.nameWithoutExtension}", src.readText())
    }
  }

  private fun buildGradleProject(): BuildResult = run("build", "--info", "--stacktrace", forwardOutput = true)

  private fun parsePackage(advice: String): String {
    val regex = Regex("package\\s+([\\w.]+)\\s*;", RegexOption.DOT_MATCHES_ALL)
    val match = regex.find(advice)
    return match?.groupValues?.getOrNull(1) ?: ""
  }

  private fun parseClassName(advice: String): String {
    val regex = Regex("class\\s+(\\w+)\\s+\\{", RegexOption.DOT_MATCHES_ALL)
    val match = regex.find(advice)
    return match?.groupValues?.getOrNull(1) ?: ""
  }
}
