package datadog.buildlogic.tagRegistry

import org.assertj.core.api.Assertions.assertThat
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome.FROM_CACHE
import org.gradle.testkit.runner.TaskOutcome.SUCCESS
import org.gradle.testkit.runner.TaskOutcome.UP_TO_DATE
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.tabletest.junit.TableTest
import java.io.File
import java.net.URLClassLoader
import java.util.jar.JarFile

class TagRegistryGeneratorPluginTest {
  @TempDir lateinit var directory: File

  @TableTest(
    """
          scenario           | plugins
          Java first         | 'java; id("dd-trace-java.tag-registry-generator")'
          tag registry first | 'id("dd-trace-java.tag-registry-generator"); java'
          """
  )
  fun `Java compilation and source consumers generate the registry in either plugin order`(
    plugins: String
  ) {
    gradleProject(
      """
      plugins { $plugins }
      java { withSourcesJar() }
      """
    )
    writeJavaDependencies()

    val result = runner("compileJava", "sourcesJar", "javadoc").build()

    assertThat(result.task(":generateKnownTags")?.outcome).isEqualTo(SUCCESS)
    assertThat(result.task(":compileJava")?.outcome).isEqualTo(SUCCESS)
    assertThat(File(directory, "build/classes/java/main/datadog/trace/api/KnownTags.class")).exists()
    assertGeneratedFiles()
    assertThat(File(directory, "src/generated")).doesNotExist()
    JarFile(File(directory, "build/libs/fixture-sources.jar")).use { jar ->
      assertThat(jar.getEntry("datadog/trace/api/KnownTags.java")).isNotNull()
    }
    assertThat(File(directory, "build/docs/javadoc/datadog/trace/api/KnownTags.html")).exists()
  }

  @Test
  fun `tag names containing Java special characters compile and retain their values`() {
    gradleProject(
      """
      plugins {
        java
        id("dd-trace-java.tag-registry-generator")
      }
      """
    )
    directory.conventionsFile(
      """
      span_types:
        base:
          tags:
            - dd-name: 'tag"\name'
              otel-name: "alias\"\\name\nline"
              span-kind-neutral: true
      """
    )
    writeJavaDependencies()

    val result = runner("compileJava").build()

    assertThat(result.task(":compileJava")?.outcome).isEqualTo(SUCCESS)
    assertGeneratedFiles()
    val classes = File(directory, "build/classes/java/main").toURI().toURL()
    URLClassLoader(arrayOf(classes), null).use { loader ->
      val names = loader.loadClass("datadog.trace.api.KnownTags").fields
        .filter { it.type == String::class.java }.map { it.get(null) }
      assertThat(names).containsExactlyInAnyOrder("tag\"\\name", "alias\"\\name\nline")
    }
  }

  @Test
  fun `compilation reuses the configuration cache and regenerates after YAML changes`() {
    gradleProject(
      """
      plugins {
        java
        id("dd-trace-java.tag-registry-generator")
      }
      """
    )
    writeJavaDependencies()
    assertThat(runner("compileJava").build().task(":generateKnownTags")?.outcome)
      .isEqualTo(SUCCESS)

    val second = runner("compileJava").build()
    assertThat(second.output).contains("Reusing configuration cache.")
    assertThat(second.task(":generateKnownTags")?.outcome).isEqualTo(UP_TO_DATE)

    directory.conventionsFile(
      """
      span_types:
        base:
          tags: [{dd-name: updated}]
      """
    )
    val third = runner("compileJava").build()
    assertThat(third.output).contains("Reusing configuration cache.")
    assertThat(third.task(":generateKnownTags")?.outcome).isEqualTo(SUCCESS)
    assertThat(third.task(":compileJava")?.outcome).isEqualTo(SUCCESS)
    assertGeneratedFiles()
    assertThat(generated().readText()).contains("UPDATED_NAME").doesNotContain("FOO_NAME")
  }

  @Test
  fun `generation can be restored from cache in a different project directory`() {
    val firstProject = File(directory, "first")
    val secondProject = File(directory, "second")
    gradleProject(projectDir = firstProject)
    gradleProject(projectDir = secondProject)
    val cache = File(directory, "cache").toURI()
    val settings = """
      rootProject.name = "fixture"
      buildCache { local { directory = uri("$cache") } }
    """
    firstProject.writeFile("settings.gradle.kts", settings)
    secondProject.writeFile("settings.gradle.kts", settings)

    assertThat(
      runner("generateKnownTags", "--build-cache", projectDir = firstProject)
        .build().task(":generateKnownTags")?.outcome
    ).isEqualTo(SUCCESS)
    val result = runner("generateKnownTags", "--build-cache", projectDir = secondProject).build()

    assertThat(result.task(":generateKnownTags")?.outcome).isEqualTo(FROM_CACHE)
    assertGeneratedFiles(File(firstProject, "build/generated/tag-registry"))
    assertGeneratedFiles(File(secondProject, "build/generated/tag-registry"))
    assertThat(generated(secondProject).readText()).isEqualTo(generated(firstProject).readText())
  }

  @Test
  fun `output follows a custom build directory and clean removes it`() {
    gradleProject(
      """
      plugins {
        id("dd-trace-java.tag-registry-generator")
        java
      }
      layout.buildDirectory.set(layout.projectDirectory.dir("custom-build"))
      """
    )
    writeJavaDependencies()

    val result = runner("compileJava").build()

    assertThat(result.task(":generateKnownTags")?.outcome).isEqualTo(SUCCESS)
    val output = File(directory, "custom-build/generated/tag-registry")
    assertGeneratedFiles(output)
    assertThat(File(directory, "build/generated")).doesNotExist()
    runner("clean").build()
    assertThat(output).doesNotExist()
  }

  @Test
  fun `input and output can be overridden without losing the producer dependency`() {
    gradleProject(
      """
      plugins {
        java
        id("dd-trace-java.tag-registry-generator")
      }
      tagRegistry {
        tagConventionsFile.set(layout.projectDirectory.file("custom.yaml"))
        destinationDirectory.set(layout.buildDirectory.dir("custom-generated"))
      }
      """
    )
    File(directory, "tag-conventions.yaml").renameTo(File(directory, "custom.yaml"))
    writeJavaDependencies()

    val result = runner("compileJava").build()

    assertThat(result.task(":generateKnownTags")?.outcome).isEqualTo(SUCCESS)
    assertGeneratedFiles(File(directory, "build/custom-generated"))
    assertThat(File(directory, "build/classes/java/main/datadog/trace/api/KnownTags.class")).exists()
  }

  @Test
  fun `help does not read or generate the domain model`() {
    gradleProject()
    File(directory, "tag-conventions.yaml").delete()

    val result = runner("help").build()

    assertThat(result.task(":generateKnownTags")).isNull()
    assertThat(generated()).doesNotExist()
  }

  private fun gradleProject(
    build: String = """
      plugins { id("dd-trace-java.tag-registry-generator") }
    """,
    projectDir: File = directory
  ) {
    projectDir.writeFile(
      "settings.gradle.kts",
      """
      rootProject.name = "fixture"
      """
    )
    projectDir.writeFile(
      "build.gradle.kts",
      build,
      """
      tagRegistry {
        tagConventionsFile.convention(layout.projectDirectory.file("tag-conventions.yaml"))
      }
      """
    )
    projectDir.conventionsFile(
      """
      span_types:
        base:
          tags: [{dd-name: foo}, {dd-name: foo.name}]
      """
    )
  }

  private fun runner(vararg arguments: String, projectDir: File = directory): GradleRunner = GradleRunner.create().withProjectDir(projectDir).withPluginClasspath()
    .withArguments(*arguments, "--configuration-cache", "--stacktrace")

  private fun generated(projectDir: File = directory): File = File(projectDir, "build/generated/tag-registry/java/datadog/trace/api/KnownTags.java")

  private fun assertGeneratedFiles(output: File = File(directory, "build/generated/tag-registry")) {
    assertThat(File(output, "java/datadog/trace/api/KnownTags.java")).isFile()
    assertThat(File(output, "resolved-tags.txt")).isFile()
    assertThat(File(output, "tag-assignment.txt")).isFile()
  }

  // Only the API surface is needed here; internal-api tests exercise the real resolver at runtime.
  private fun writeJavaDependencies() {
    directory.writeFile(
      "src/main/java/datadog/trace/api/KnownTagCodec.java",
      """
      package datadog.trace.api;
      public final class KnownTagCodec {
        public static final int DIRECTION_INBOUND = 0;
        public static final int DIRECTION_OUTBOUND = 1;
        public static final int DIRECTION_NONE = 2;
        static final long SHARED_NAME = -1L;
        static final long DIRECTION_SCOPED_NAME = -2L;
        public interface Resolver {
          String nameOf(long id);
          String openTelemetryNameOf(long id, int direction);
          long lookup(String name);
          long directionalKeyOf(String name, int direction);
        }
        public static int serialNum(long id) { return (int) (id >>> 48); }
      }
      """
    )
    directory.writeFile(
      "src/main/java/datadog/trace/util/StringIndex.java",
      """
      package datadog.trace.util;
      public final class StringIndex {
        public static final class Data {
          public final int[] hashes;
          public final String[] names;
          public Data(String[] names) { this.names = names; this.hashes = new int[names.length]; }
        }
        public static final class EmbeddingSupport {
          public static Data create(String[] names) { return new Data(names); }
          public static int indexOf(int[] hashes, String[] names, String name) {
            for (int i = 0; i < names.length; i++) if (names[i].equals(name)) return i;
            return -1;
          }
        }
      }
      """
    )
  }
}
