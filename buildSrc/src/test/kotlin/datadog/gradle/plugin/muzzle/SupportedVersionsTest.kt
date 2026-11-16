package datadog.gradle.plugin.muzzle

import datadog.gradle.plugin.muzzle.SupportedVersions.Directive
import datadog.gradle.plugin.muzzle.SupportedVersions.RuntimeSupport
import datadog.gradle.plugin.muzzle.SupportedVersions.SupportedVersionsFragment
import datadog.gradle.plugin.muzzle.SupportedVersions.TestSuite
import org.assertj.core.api.Assertions.assertThat
import org.gradle.api.JavaVersion
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class SupportedVersionsTest {

  @TempDir
  lateinit var tempDir: File

  @Test
  fun `parses lockfile entries by library and version`() {
    val lockfile = SupportedVersions.parseLockfile(
      """
      # This is a Gradle generated file for dependency locking.
      com.graphql-java:graphql-java:20.0=compileClasspath,testRuntimeClasspath
      com.graphql-java:graphql-java:22.4=latestDepTestRuntimeClasspath
      empty=annotationProcessor
      """.trimIndent()
    )

    assertThat(lockfile).isEqualTo(
      mapOf(
        "com.graphql-java:graphql-java" to mapOf(
          "20.0" to setOf("compileClasspath", "testRuntimeClasspath"),
          "22.4" to setOf("latestDepTestRuntimeClasspath"),
        )
      )
    )
  }

  @Test
  fun `maps test suites to the Java versions CI runs them on`() {
    val runtimes = SupportedVersions.testedRuntimes(
      listOf(
        suite("test"),
        suite("latestDepTest"),
        suite("java11Test", minJavaVersion = JavaVersion.VERSION_11),
        suite("legacyTest", maxJavaVersion = JavaVersion.VERSION_17),
        suite("noJava8Test", excludeJdk = listOf("8")),
      )
    )

    assertThat(runtimes).isEqualTo(
      mapOf(
        "testRuntimeClasspath" to setOf(8, 11, 17, 21, 25),
        "latestDepTestRuntimeClasspath" to setOf(8, 17, 21, 25),
        "java11TestRuntimeClasspath" to setOf(11, 17, 21, 25),
        "legacyTestRuntimeClasspath" to setOf(8, 11, 17),
        "noJava8TestRuntimeClasspath" to setOf(11, 17, 21, 25),
      )
    )
  }

  @Test
  fun `reports ranges by directive Java version and tested versions by suite Java versions`() {
    val fragments = SupportedVersions.fragments(
      listOf(
        Directive("com.graphql-java:graphql-java", "[20.0, 21.0)", null, mapOf("graphql-java" to true)),
        Directive("com.graphql-java:graphql-java", "[21.0, 22.0)", 11, mapOf("graphql-java" to true)),
      ),
      mapOf(
        "com.graphql-java:graphql-java" to mapOf(
          "20.0" to setOf("compileClasspath", "testRuntimeClasspath"),
          "21.5" to setOf("latestDepTestRuntimeClasspath"),
        )
      ),
      mapOf(
        "testRuntimeClasspath" to setOf(8, 11),
        "latestDepTestRuntimeClasspath" to setOf(11),
      ),
    )

    assertThat(fragments).containsExactly(
      SupportedVersionsFragment(
        "com.graphql-java:graphql-java",
        "graphql-java",
        true,
        mapOf(
          8 to RuntimeSupport(setOf("[20.0, 21.0)"), setOf("20.0")),
          11 to RuntimeSupport(setOf("[20.0, 21.0)", "[21.0, 22.0)"), setOf("20.0", "21.5")),
        ),
      )
    )
  }

  @Test
  fun `does not report Java versions or libraries without tested versions`() {
    val fragments = SupportedVersions.fragments(
      listOf(
        Directive("org.example:lib", "[1.0,)", null, mapOf("example" to true)),
        Directive("org.example:untested", "[1.0,)", null, mapOf("example" to true)),
      ),
      mapOf("org.example:lib" to mapOf("1.0" to setOf("latestDepTestRuntimeClasspath"))),
      mapOf(
        "testRuntimeClasspath" to setOf(8),
        "latestDepTestRuntimeClasspath" to setOf(17),
      ),
    )

    assertThat(fragments).containsExactly(
      SupportedVersionsFragment("org.example:lib", "example", true, mapOf(17 to RuntimeSupport(setOf("[1.0,)"), setOf("1.0")))),
    )
  }

  @Test
  fun `CI Java versions match the instrumentation test jobs`() {
    val ci = File("../.gitlab-ci.yml").readText()
    val testJvms = Regex("""- testJvm: &test_jvms\n((?:\s+- "[^"]+"\n)+)""").find(ci)!!.groupValues[1]
    val latestDepTestJvms = Regex("""(?s)\ntest_inst_latest:.*?testJvm: \[([^]]+)]""").find(ci)!!.groupValues[1]

    assertThat(SupportedVersions.TEST_JAVA_VERSIONS).isEqualTo(javaVersions(testJvms))
    assertThat(SupportedVersions.LATEST_DEP_TEST_JAVA_VERSIONS).isEqualTo(javaVersions(latestDepTestJvms))
  }

  /** The plain `testJvm` majors, without vendor JVMs like `zulu8` and the moving `tip`. */
  private fun javaVersions(testJvms: String): List<Int> =
    Regex(""""(\d+)"""").findAll(testJvms).map { it.groupValues[1].toInt() }.toList()

  @Test
  fun `merges fragments of the same library and integration`() {
    val merged = SupportedVersions.merge(
      listOf(
        SupportedVersionsFragment("org.example:lib", "example", false, mapOf(8 to RuntimeSupport(setOf("[1,2)"), setOf("1.0")))),
        SupportedVersionsFragment("org.example:lib", "example", true, mapOf(8 to RuntimeSupport(setOf("[2,3)"), setOf("2.0")))),
        SupportedVersionsFragment("org.example:another", "another", true, mapOf(8 to RuntimeSupport(setOf("[1,)"), setOf("1.0")))),
      )
    )

    assertThat(merged).containsExactly(
      SupportedVersionsFragment("org.example:another", "another", true, mapOf(8 to RuntimeSupport(setOf("[1,)"), setOf("1.0")))),
      SupportedVersionsFragment("org.example:lib", "example", true, mapOf(8 to RuntimeSupport(setOf("[1,2)", "[2,3)"), setOf("1.0", "2.0")))),
    )
  }

  @Test
  fun `groups Java versions sharing the same range and tested versions`() {
    val entries = SupportedVersions.entries(
      listOf(
        SupportedVersionsFragment(
          "org.example:lib",
          "example",
          true,
          mapOf(
            17 to RuntimeSupport(setOf("[1,2)", "[2,3)"), setOf("2.0", "1.10", "1.2")),
            8 to RuntimeSupport(setOf("[1,2)"), setOf("1.2")),
            11 to RuntimeSupport(setOf("[1,2)"), setOf("1.2")),
          ),
        )
      )
    )

    assertThat(entries).containsExactly(
      mapOf(
        "libraryName" to "org.example:lib",
        "integrationName" to "example",
        "autoInstrumented" to true,
        "versions" to listOf(
          mapOf("testedRuntimes" to mapOf("java" to listOf("8", "11")), "supportedRange" to "[1,2)", "tested" to listOf("1.2")),
          mapOf("testedRuntimes" to mapOf("java" to listOf("17")), "supportedRange" to "[1,3)", "tested" to listOf("1.2", "1.10", "2.0")),
        ),
      )
    )
  }

  @Test
  fun `merges ranges into a Maven range union without overlaps`() {
    assertThat(SupportedVersions.supportedRange(setOf("[2.0, 3)"))).isEqualTo("[2.0,3)")
    assertThat(SupportedVersions.supportedRange(setOf("[2.0,3)", "(,1.0]", "[1.5,2.0)"))).isEqualTo("(,1.0],[1.5,3)")
    assertThat(SupportedVersions.supportedRange(setOf("[14.0,20)", "[14.0,)", "[20.0,21.0)"))).isEqualTo("[14.0,)")
    assertThat(SupportedVersions.supportedRange(setOf("[1.9,1.16],[5,6)", "[1.10,2)"))).isEqualTo("[1.9,2),[5,6)")
    assertThat(SupportedVersions.supportedRange(setOf("[1,2)", "(2,3)"))).isEqualTo("[1,2),(2,3)")
    assertThat(SupportedVersions.supportedRange(setOf("1.0", "[2.0,]"))).isEqualTo("[1.0],[2.0,)")
    assertThat(SupportedVersions.supportedRange(setOf("[,]", "[2.0,)"))).isEqualTo("*")
  }

  @Test
  fun `round trips fragments through their file`() {
    val fragments = listOf(
      SupportedVersionsFragment("org.example:lib", "example", true, mapOf(8 to RuntimeSupport(setOf("[1,2)"), setOf("1.0", "1.1")))),
    )
    val file = File(tempDir, "fragments/lib.json")

    SupportedVersions.writeFragments(file, fragments)

    assertThat(SupportedVersions.readFragments(file)).isEqualTo(fragments)
  }

  private fun suite(
    name: String,
    minJavaVersion: JavaVersion? = null,
    maxJavaVersion: JavaVersion? = null,
    excludeJdk: List<String> = emptyList(),
  ) = TestSuite(name, minJavaVersion, maxJavaVersion, emptyList(), excludeJdk, emptyList())
}
