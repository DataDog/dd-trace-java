package datadog.gradle.plugin.csi

import datadog.gradle.plugin.GradleFixture
import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.File
import java.nio.file.FileSystems
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

  private val dependencyBuildGradle = """
    $buildGradle
    dependencies { runtimeOnly(files("dependency.jar")) }
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

  private val iastDependencyAdvice = """
    import datadog.trace.agent.tooling.csi.CallSite;
    import datadog.trace.api.iast.IastCallSites;
    import datadog.trace.api.iast.Propagation;

    @CallSite(spi = IastCallSites.class)
    public class BeforeAdviceCallSite {
      @Propagation
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
    createCachingProject("$buildGradle\n$dependencyConfiguration")
    writeJavaSource(
      "GeneratedCallSiteUsage",
      "class GeneratedCallSiteUsage { BeforeAdviceCallSites callSites; }",
      sourceSet = "test"
    )

    val first = buildGradleProject()
    assertEquals(TaskOutcome.SUCCESS, first.task(":generateCallSiteJava")?.outcome, first.output)
    assertTrue(buildFile("csi/BeforeAdviceCallSites.java").exists())
    assertEquals(TaskOutcome.SUCCESS, first.task(":compileCsiJava")?.outcome, first.output)
    assertEquals(TaskOutcome.SUCCESS, first.task(":compileTestJava")?.outcome, first.output)

    val unchanged = run("generateCallSiteJava", "--info", "--stacktrace")
    assertEquals(TaskOutcome.UP_TO_DATE, unchanged.task(":generateCallSiteJava")?.outcome, unchanged.output)

    val restored = run("clean", "generateCallSiteJava", "--info")
    assertEquals(TaskOutcome.FROM_CACHE, restored.task(":generateCallSiteJava")?.outcome, restored.output)

    writeDependencyJar("")

    val changed = run("generateCallSiteJava", "--info", "--stacktrace", expectFailure = true)
    assertEquals(TaskOutcome.UP_TO_DATE, changed.task(":compileJava")?.outcome, changed.output)
    assertEquals(TaskOutcome.FAILED, changed.task(":generateCallSiteJava")?.outcome, changed.output)
    assertTrue(changed.output.contains("UNRESOLVED_METHOD"), changed.output)
    assertFalse(buildFile("csi/BeforeAdviceCallSites.java").exists())
  }

  @ParameterizedTest
  @ValueSource(strings = ["same checkout", "relocated checkout", "relocated IAST checkout"])
  fun `generated sources are restored from cache`(scenario: String) {
    val iast = scenario == "relocated IAST checkout"
    createCachingProject(advice = if (iast) iastDependencyAdvice else dependencyAdvice)
    if (iast) {
      val repository = File(System.getProperty("user.dir")).parentFile
      listOf(
        "datadog.trace.api.iast.IastCallSites",
        "datadog.trace.api.iast.Propagation",
        "datadog.trace.api.iast.telemetry.Verbosity",
      ).forEach {
        writeJavaSource(it, File(repository, "internal-api/src/main/java/${it.replace('.', '/')}.java").readText())
      }
    }

    val first = run("generateCallSiteJava", "--info")
    assertEquals(TaskOutcome.SUCCESS, first.task(":generateCallSiteJava")?.outcome, first.output)
    val expected = buildFile("csi/BeforeAdviceCallSites.java").readBytes()
    if (iast) {
      assertTrue(String(expected, Charsets.UTF_8).contains("INSTRUMENTED_PROPAGATION"))
    }

    val unchanged = run("generateCallSiteJava", "--info")
    assertEquals(TaskOutcome.UP_TO_DATE, unchanged.task(":generateCallSiteJava")?.outcome, unchanged.output)

    val checkout = if (scenario != "same checkout") {
      dir("relocated").also { target ->
        listOf("build.gradle.kts", "settings.gradle.kts", "gradle.properties", "src", "dependency.jar", "buildSrc").forEach {
          assertTrue(file(it).copyRecursively(File(target, it)))
        }
      }
    } else {
      assertTrue(buildDir.deleteRecursively())
      projectDir
    }
    writeFile("unrelated.txt", "This file is outside the generator inputs.")

    val restored = run("generateCallSiteJava", "--info", gradleProjectDir = checkout)
    assertEquals(TaskOutcome.FROM_CACHE, restored.task(":generateCallSiteJava")?.outcome, restored.output)
    val generated = File(checkout, "build/csi/BeforeAdviceCallSites.java")
    assertArrayEquals(expected, generated.readBytes())

    val fresh = run("generateCallSiteJava", "--no-build-cache", "--rerun-tasks", gradleProjectDir = checkout)
    assertEquals(TaskOutcome.SUCCESS, fresh.task(":generateCallSiteJava")?.outcome, fresh.output)
    assertArrayEquals(expected, generated.readBytes())
  }

  @ParameterizedTest
  @ValueSource(strings = ["sources", "generator JAR"])
  fun `cache follows generator inputs`(changedInput: String) {
    createCachingProject()

    val first = run("generateCallSiteJava", "--info")
    assertEquals(TaskOutcome.SUCCESS, first.task(":generateCallSiteJava")?.outcome, first.output)
    val adviceClass = buildFile("classes/java/main/BeforeAdviceCallSite.class").readBytes()
    val expected = buildFile("csi/BeforeAdviceCallSites.java").readBytes()

    if (changedInput == "sources") {
      file("src/main/java/BeforeAdviceCallSite.java").appendText("\n// Changed source, unchanged bytecode.\n")
    } else {
      val generator = file("buildSrc/call-site-instrumentation-plugin/build/libs/call-site-instrumentation-plugin-all.jar")
      FileSystems.newFileSystem(generator.toPath(), javaClass.classLoader).use {
        Files.write(it.getPath("/cache-test.txt"), "Changed generator content".toByteArray(Charsets.UTF_8))
      }
    }

    val changed = run("generateCallSiteJava", "--info")
    assertEquals(TaskOutcome.SUCCESS, changed.task(":generateCallSiteJava")?.outcome, changed.output)
    assertArrayEquals(adviceClass, buildFile("classes/java/main/BeforeAdviceCallSite.class").readBytes())
    assertArrayEquals(expected, buildFile("csi/BeforeAdviceCallSites.java").readBytes())

    val restored = run("clean", "generateCallSiteJava", "--info")
    assertEquals(TaskOutcome.FROM_CACHE, restored.task(":generateCallSiteJava")?.outcome, restored.output)
    assertArrayEquals(expected, buildFile("csi/BeforeAdviceCallSites.java").readBytes())
  }

  @ParameterizedTest
  @ValueSource(strings = ["vendor", "runtimeVersion", "vmVersion"])
  fun `cache follows the selected generator JVM`(changedField: String) {
    createCachingProject(
      """
        import org.gradle.jvm.toolchain.JavaInstallationMetadata
        import org.gradle.jvm.toolchain.JavaLauncher

        $dependencyBuildGradle

        class SelectedLauncher(
          private val delegate: JavaLauncher,
          private val changedField: String,
        ) : JavaLauncher by delegate {
          override fun getMetadata(): JavaInstallationMetadata =
            object : JavaInstallationMetadata by delegate.metadata {
              override fun getVendor() =
                if (changedField == "vendor") "different-vendor" else delegate.metadata.vendor
              override fun getJavaRuntimeVersion() =
                if (changedField == "runtimeVersion") "different-runtime" else delegate.metadata.javaRuntimeVersion
              override fun getJvmVersion() =
                if (changedField == "vmVersion") "different-vm" else delegate.metadata.jvmVersion
            }
        }

        val launcher = javaToolchains.launcherFor {}
        val changedField = providers.gradleProperty("changedJvmField").orElse("")
        tasks.named<JavaExec>("generateCallSiteJava") {
          javaLauncher.set(launcher.zip(changedField) { delegate, field -> SelectedLauncher(delegate, field) })
        }
      """
    )

    val first = run("generateCallSiteJava", "--info")
    assertEquals(TaskOutcome.SUCCESS, first.task(":generateCallSiteJava")?.outcome, first.output)

    val restored = run("clean", "generateCallSiteJava", "--info")
    assertEquals(TaskOutcome.FROM_CACHE, restored.task(":generateCallSiteJava")?.outcome, restored.output)

    val changed = run("generateCallSiteJava", "--info", "-PchangedJvmField=$changedField")
    assertEquals(TaskOutcome.SUCCESS, changed.task(":generateCallSiteJava")?.outcome, changed.output)
  }

  @Test
  fun `cache follows effective JVM arguments`() {
    createCachingProject(
      """
        $dependencyBuildGradle

        val language = providers.gradleProperty("generatorLanguage").orElse("en")
        tasks.named<JavaExec>("generateCallSiteJava") {
          jvmArgumentProviders.add { listOf("-Duser.language=${'$'}{language.get()}") }
        }
      """
    )

    val first = run("generateCallSiteJava", "--info")
    assertEquals(TaskOutcome.SUCCESS, first.task(":generateCallSiteJava")?.outcome, first.output)

    val restored = run("clean", "generateCallSiteJava", "--info")
    assertEquals(TaskOutcome.FROM_CACHE, restored.task(":generateCallSiteJava")?.outcome, restored.output)

    val changed = run("generateCallSiteJava", "--info", "-PgeneratorLanguage=tr")
    assertEquals(TaskOutcome.SUCCESS, changed.task(":generateCallSiteJava")?.outcome, changed.output)
  }

  @ParameterizedTest
  @ValueSource(strings = ["JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS", "JDK_JAVA_OPTIONS"])
  fun `cache follows JVM environment options`(variable: String) {
    createCachingProject()
    val optionVariables = setOf("JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS", "JDK_JAVA_OPTIONS")

    val first = run("generateCallSiteJava", "--info", unsetEnv = optionVariables)
    assertEquals(TaskOutcome.SUCCESS, first.task(":generateCallSiteJava")?.outcome, first.output)

    val restored = run("clean", "generateCallSiteJava", "--info", unsetEnv = optionVariables)
    assertEquals(TaskOutcome.FROM_CACHE, restored.task(":generateCallSiteJava")?.outcome, restored.output)

    val changed = run(
      "generateCallSiteJava",
      "--info",
      unsetEnv = optionVariables - variable,
      env = mapOf(variable to "-Dcsi.cache.test=true")
    )
    assertEquals(TaskOutcome.SUCCESS, changed.task(":generateCallSiteJava")?.outcome, changed.output)
  }

  @Test
  fun `generation removes outputs that no longer match the suffix`() {
    createCachingProject(
      """
        $dependencyBuildGradle
        csi { suffix.set(providers.gradleProperty("csiSuffix").orElse("CallSite")) }
      """
    )

    val first = run("generateCallSiteJava", "--info")
    assertEquals(TaskOutcome.SUCCESS, first.task(":generateCallSiteJava")?.outcome, first.output)
    assertTrue(buildFile("csi/BeforeAdviceCallSites.java").exists())

    val changed = run("generateCallSiteJava", "--info", "-PcsiSuffix=AbsentCallSite")
    assertEquals(TaskOutcome.SUCCESS, changed.task(":generateCallSiteJava")?.outcome, changed.output)
    assertFalse(buildFile("csi/BeforeAdviceCallSites.java").exists())

    val restored = run("clean", "generateCallSiteJava", "--info", "-PcsiSuffix=AbsentCallSite")
    assertEquals(TaskOutcome.FROM_CACHE, restored.task(":generateCallSiteJava")?.outcome, restored.output)
    assertFalse(buildFile("csi/BeforeAdviceCallSites.java").exists())

    val original = run("generateCallSiteJava", "--info")
    assertEquals(TaskOutcome.FROM_CACHE, original.task(":generateCallSiteJava")?.outcome, original.output)
    assertTrue(buildFile("csi/BeforeAdviceCallSites.java").exists())
  }

  private fun createCachingProject(gradleFile: String = dependencyBuildGradle, advice: String = dependencyAdvice) {
    createGradleProject(gradleFile, advice)
    writeDependencyJar("public static void call(String value) {}")
    writeSettings(
      """
        rootProject.name = "csi-cache"
        buildCache { local { directory = file("${file("task-cache").invariantSeparatorsPath}") } }
      """
    )
    writeGradleProperties("org.gradle.caching=true")
    writeRootProject("csi { rootFolder.set(layout.projectDirectory.asFile) }", append = true)
  }

  @ParameterizedTest
  @ValueSource(booleans = [true, false])
  fun `generation supports test fixtures in the same project`(fixturesFirst: Boolean) {
    val script = if (fixturesFirst) {
      dependencyBuildGradle.replace("id(\"java\")", "id(\"java-test-fixtures\")")
    } else {
      dependencyBuildGradle.replace(
        "id(\"dd-trace-java.call-site-instrumentation\")",
        "id(\"dd-trace-java.call-site-instrumentation\")\nid(\"java-test-fixtures\")"
      )
    }
    createCachingProject(script)
    writeJavaSource("Fixture", "public class Fixture {}", sourceSet = "testFixtures")

    val result = run("generateCallSiteJava", "--info", "--stacktrace")

    assertEquals(TaskOutcome.SUCCESS, result.task(":generateCallSiteJava")?.outcome, result.output)
    assertNull(result.task(":testFixturesJar"), result.output)
  }

  @ParameterizedTest
  @ValueSource(booleans = [false, true])
  fun `generation retains external test fixtures`(composite: Boolean) {
    val dependency = if (composite) "\"example:dependency:1.0\"" else "project(\":dependency\")"
    val script = buildGradle.replace("id(\"java\")", "id(\"java-test-fixtures\")")
    createGradleProject(
      "$script\ndependencies { testImplementation(testFixtures($dependency)) }",
      dependencyAdvice
    )
    writeJavaSource("OwnFixture", "public class OwnFixture {}", sourceSet = "testFixtures")
    val dependencyBuild = """
      plugins { id("java-test-fixtures") }
      group = "example"
      version = "1.0"
      java {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
      }
    """
    if (composite) {
      writeSettings("includeBuild(\"dependency\")")
      writeFile("dependency/settings.gradle.kts", "rootProject.name = \"dependency\"")
      writeFile("dependency/build.gradle.kts", dependencyBuild)
    } else {
      addSubproject("dependency", dependencyBuild)
    }
    writeJavaSource(
      "example.Target",
      "package example; public class Target { public static void call(String value) {} }",
      sourceSet = "testFixtures",
      projectPath = "dependency"
    )

    val result = run("generateCallSiteJava", "--info", "--stacktrace")

    assertEquals(TaskOutcome.SUCCESS, result.task(":dependency:testFixturesJar")?.outcome, result.output)
    assertEquals(TaskOutcome.SUCCESS, result.task(":generateCallSiteJava")?.outcome, result.output)
    assertNull(result.task(":testFixturesJar"), result.output)
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
