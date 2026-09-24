package datadog.gradle.plugin.dump

import datadog.gradle.plugin.GradleFixture
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertNotNull
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import java.io.File
import java.util.Properties

class DumpHangedTestIntegrationTest : GradleFixture() {
  companion object {
    /** A `jcmd <pid> Thread.print`, excluding the all-JVM `jcmd 0` sweep. */
    private val PER_PROCESS_THREAD_PRINT = Regex("""jcmd [1-9]\d* Thread\.print""")
  }

  @Test
  fun `should not take dumps`() {
    val output = runGradleTest(testSleepMillis = 1000)

    // Assert Gradle output has no evidence of taking dumps.
    assertFalse(output.contains("Taking dumps after 15 seconds delay for :test"))
    assertFalse(output.contains("Requesting stop of task ':test' as it has exceeded its configured timeout of 20s."))

    assertTrue(buildDir.exists()) // Assert build happened.
    assertFalse(buildFile("dumps").exists()) // Assert no dumps created.
  }

  @Test
  fun `should recognize IBM JVM vendor`() {
    // `JavaInstallationMetadata.getVendor()` reports the display name, `java.vendor` the raw one.
    assertTrue(isIbmJvmVendor("IBM"))
    assertTrue(isIbmJvmVendor("IBM Corporation"))
    assertTrue(isIbmJvmVendor("ibm corporation"))
    assertFalse(isIbmJvmVendor("Eclipse Adoptium"))
    assertFalse(isIbmJvmVendor("Oracle Corporation"))
  }

  @Test
  fun `should not configure IBM javacore directory on non-IBM JVM`() {
    assumeTrue(!isIbmJvmVendor(System.getProperty("java.vendor")), "needs a non-IBM JVM")

    val output = runGradleTest(testSleepMillis = 1000, assertNoIbmJavaCoreDir = true)

    // The generated assertion only counts if the build ran it and it passed.
    assertTrue(output.any { it.startsWith("BUILD SUCCESSFUL") }, output.joinToString("\n"))
    assertEquals(listOf("ibmJavaCoreDirIsNotSet", "test"), testCaseNames().sorted())
  }

  @Test
  @EnabledOnOs(OS.LINUX, OS.MAC)
  fun `should collect javacore dumps on IBM JVM`() {
    val ibmJdk = findIbmJdk()
    assumeTrue(ibmJdk != null, "needs a locally installed IBM JVM")

    val output = runGradleTest(testSleepMillis = 25_000, ibmJdk = ibmJdk)

    // `kill -3` replaces the per-process `jcmd Thread.print`, and no heap dump is attempted.
    assertTrue(output.any { it.startsWith("Starting dump command for :test:") && it.contains("kill -3") })
    assertFalse(output.any { PER_PROCESS_THREAD_PRINT.containsMatchIn(it) })
    assertFalse(output.any { it.contains("GC.heap_dump") })

    // Landing here proves IBM_JAVACOREDIR reached the forked JVM; the default is its working dir.
    val dumps = buildFile("dumps").listFiles().orEmpty()
    assertTrue(
      dumps.any { it.name.startsWith("javacore.") && it.name.endsWith(".txt") },
      "no javacore in ${dumps.map { it.name }}"
    )
  }

  @Test
  fun `should take dumps`() {
    val output = runGradleTest(testSleepMillis = 25_0000)

    // Assert Gradle output has evidence of taking dumps.
    assertTrue(output.contains("Taking dumps after 15 seconds delay for :test"))
    assertTrue(output.contains("Requesting stop of task ':test' as it has exceeded its configured timeout of 20s."))
    assertTrue(buildDir.exists()) // Assert build happened.

    val dumps = buildFile("dumps")
    assertTrue(dumps.exists()) // Assert dumps created.
    assertTrue(output.any { it.startsWith("Starting dump command for :test:") && it.contains("GC.heap_dump") })
    assertTrue(output.any { it.startsWith("Completed dump command for :test") && it.contains("Thread.print -l") })

    // Assert actual dumps created.
    val dumpFiles = dumps.list()
    assertNotNull(dumpFiles.find { it.endsWith(".hprof") })
    assertNotNull(dumpFiles.find { it.startsWith("all-thread-dumps") })
  }

  @Test
  fun `should start dumps three minutes before timeout by default`() {
    val output = runGradleTest(testSleepMillis = 10_000, timeoutSeconds = 185, dumpOffset = null)

    assertTrue(output.contains("Taking dumps after 5 seconds delay for :test"))
    assertFalse(output.any { it.contains("has exceeded its configured timeout") })
  }

  @Test
  fun `should report directory failures with stack trace`() {
    writeFile("build/dumps", "This file prevents creating the dump directory")

    val output = runGradleTest(testSleepMillis = 25_000)

    assertTrue(output.any { it.contains("Taking dumps failed for :test") })
    assertTrue(output.any { it.contains("java.io.IOException: Could not create dump directory") })
    assertTrue(output.any { it.contains("DumpHangedTestPlugin.takeDump(") })
  }

  @Test
  @EnabledOnOs(OS.LINUX, OS.MAC)
  fun `should collect all thread dumps before attempting heap dumps`() {
    val jcmd = writeFile(
      "bin/jcmd",
      """
      #!/bin/sh
      if [ "${'$'}2" = "GC.heap_dump" ]; then
        echo "Synthetic heap dump failure" >&2
        exit 7
      fi
      echo "Synthetic thread dump for PID ${'$'}1"
      """
    )
    assertTrue(jcmd.setExecutable(true))
    // Start a separate daemon so subprocess lookup uses this test's PATH.
    writeGradleProperties("org.gradle.jvmargs=-DdumpFailureTest=true")

    val output = runGradleTest(
      testSleepMillis = 25_000,
      env = mapOf("PATH" to "${jcmd.parent}${File.pathSeparator}${System.getenv("PATH")}")
    )

    assertTrue(output.any { it.startsWith("Dump command failed for :test:") && it.contains("GC.heap_dump") })
    assertTrue(output.any { it.contains("java.io.IOException: Process failed with exit code 7") })
    assertTrue(output.any { it.contains("DumpHangedTestPlugin.runCmd(") })
    val dumps = buildFile("dumps").listFiles().orEmpty()
    assertTrue(dumps.any { it.name.contains("-thread-dump-") && it.readText().startsWith("Synthetic thread dump") })
    assertTrue(dumps.any { it.name.startsWith("all-thread-dumps-") && it.readText().contains("PID 0") })
    assertTrue(output.any { it.startsWith("Finished dump collection for :test;") })
    val firstHeapDump = output.indexOfFirst { it.startsWith("Starting dump command") && it.contains("GC.heap_dump") }
    val lastThreadDump = output.indexOfLast { it.startsWith("Completed dump command") && it.contains("Thread.print") }
    assertTrue(lastThreadDump >= 0 && firstHeapDump > lastThreadDump)
  }

  /** Runs a Gradle build of a single-test project under the dump plugin. */
  private fun runGradleTest(
    testSleepMillis: Long,
    env: Map<String, String> = emptyMap(),
    timeoutSeconds: Long = 20,
    dumpOffset: Long? = 5,
    ibmJdk: IbmJdk? = null,
    assertNoIbmJavaCoreDir: Boolean = false
  ): List<String> {
    val javaCoreDirImport = if (assertNoIbmJavaCoreDir) {
      "import static org.junit.jupiter.api.Assertions.assertNull;"
    } else {
      ""
    }
    val javaCoreDirTest = if (assertNoIbmJavaCoreDir) {
      """
      @Test
      public void ibmJavaCoreDirIsNotSet() {
          assertNull(System.getenv("IBM_JAVACOREDIR"));
      }
      """
    } else {
      ""
    }

    if (ibmJdk != null) {
      writeGradleProperties(
        "org.gradle.java.installations.paths=${ibmJdk.home.absolutePath}",
        append = true
      )
    }
    val ibmToolchain = ibmJdk?.let {
      """
      java {
        toolchain {
          languageVersion.set(JavaLanguageVersion.of(${it.majorVersion}))
          vendor.set(JvmVendorSpec.IBM)
        }
      }
      """
    }.orEmpty()

    writeSettings("""rootProject.name = "test-project"""")

    writeRootProject(
      """
      import java.time.Duration
      import org.gradle.api.tasks.testing.Test
      import org.gradle.jvm.toolchain.JavaLanguageVersion
      import org.gradle.jvm.toolchain.JvmVendorSpec

      plugins {
        id("java")
        id("dd-trace-java.dump-hanged-test")
      }

      group = "datadog.dump.test"

      $ibmToolchain

      repositories {
        mavenCentral()
      }

      dependencies {
        testImplementation(platform("org.junit:junit-bom:5.10.0"))
        testImplementation("org.junit.jupiter:junit-jupiter")
        testRuntimeOnly("org.junit.platform:junit-platform-launcher")
      }

      dumpHangedTest {
        ${dumpOffset?.let { "dumpOffset.set($it)" } ?: ""}
      }

      tasks.withType<Test>().configureEach {
        timeout.set(Duration.ofSeconds($timeoutSeconds))

        useJUnitPlatform()
      }
      """
    )

    writeJavaSource(
      "SimpleTest",
      """
      $javaCoreDirImport
      import org.junit.jupiter.api.Test;

      public class SimpleTest {
          $javaCoreDirTest

          @Test
          public void test() throws InterruptedException {
              Thread.sleep($testSleepMillis);
          }
      }
      """,
      sourceSet = "test"
    )

    return run("test", env = env, forwardOutput = true).output.lines()
  }

  /** Names of the test cases the generated project reported, from its JUnit XML report. */
  private fun testCaseNames(): List<String> {
    val report = buildFile("test-results/test/TEST-SimpleTest.xml")
    assertTrue(report.isFile, "missing test report $report")
    val testCases = parseXml(report).getElementsByTagName("testcase")
    return (0 until testCases.length)
      .map { testCases.item(it).attributes.getNamedItem("name").nodeValue.removeSuffix("()") }
  }

  /** A locally installed IBM JVM, as resolved from its `release` file. */
  private data class IbmJdk(val home: File, val majorVersion: Int)

  /** Newest installed IBM JVM, from the CI `JAVA_<dist><version>_HOME` vars or the platform JVM dirs. */
  private fun findIbmJdk(): IbmJdk? {
    val candidates = mutableListOf<File>()
    System.getenv()
      .filterKeys { it.matches(Regex("JAVA_(IBM|SEMERU)\\d+_HOME")) }
      .values.mapTo(candidates, ::File)
    for (root in listOf("/Library/Java/JavaVirtualMachines", "/usr/lib/jvm")) {
      for (jvm in File(root).listFiles().orEmpty()) {
        candidates += File(jvm, "Contents/Home") // macOS bundle layout
        candidates += jvm
      }
    }
    return candidates.mapNotNull { it.toIbmJdk() }.maxByOrNull { it.majorVersion }
  }

  private fun File.toIbmJdk(): IbmJdk? {
    val release = File(this, "release").takeIf { it.isFile } ?: return null
    val properties = Properties().apply { release.inputStream().use { load(it) } }
    // Values in `release` are quoted, e.g. IMPLEMENTOR="IBM Corporation".
    fun property(name: String) = properties.getProperty(name).orEmpty().trim('"')

    if (!isIbmJvmVendor(property("IMPLEMENTOR"))) return null
    val major = property("JAVA_VERSION").removePrefix("1.").substringBefore('.').toIntOrNull()
    return major?.let { IbmJdk(this, it) }
  }
}
