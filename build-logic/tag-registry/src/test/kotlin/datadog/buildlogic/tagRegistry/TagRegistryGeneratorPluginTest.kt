package datadog.buildlogic.tagRegistry

import org.assertj.core.api.Assertions.assertThat
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome.FROM_CACHE
import org.gradle.testkit.runner.TaskOutcome.SUCCESS
import org.gradle.testkit.runner.TaskOutcome.UP_TO_DATE
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.File
import java.net.URLClassLoader
import java.util.jar.JarFile

class TagRegistryGeneratorPluginTest {
  @TempDir lateinit var directory: File

  @ParameterizedTest
  @ValueSource(booleans = [true, false])
  fun `Java compilation and source consumers generate the registry in either plugin order`(
    javaFirst: Boolean
  ) {
    val plugins = if (javaFirst) {
      "java; id(\"dd-trace-java.tag-registry-generator\")"
    } else {
      "id(\"dd-trace-java.tag-registry-generator\"); java"
    }
    fixture("plugins { $plugins }\njava { withSourcesJar() }")
    writeJavaDependencies()

    val result = runner("compileJava", "sourcesJar", "javadoc").build()

    assertThat(result.task(":generateKnownTags")?.outcome).isEqualTo(SUCCESS)
    assertThat(result.task(":compileJava")?.outcome).isEqualTo(SUCCESS)
    assertThat(File(directory, "build/classes/java/main/datadog/trace/api/KnownTags.class")).exists()
    assertThat(generated()).exists()
    assertThat(File(directory, "src/generated")).doesNotExist()
    JarFile(File(directory, "build/libs/fixture-sources.jar")).use { jar ->
      assertThat(jar.getEntry("datadog/trace/api/KnownTags.java")).isNotNull()
    }
    assertThat(File(directory, "build/docs/javadoc/datadog/trace/api/KnownTags.html")).exists()
  }

  @Test
  fun `tag names containing Java special characters compile and retain their values`() {
    fixture("plugins { java; id(\"dd-trace-java.tag-registry-generator\") }")
    File(directory, "tag-conventions.yaml").writeText(
      """
      span_types:
        base:
          tags:
            - dd-name: 'tag"\name'
              otel-name: "alias\"\\name\nline"
              span-kind-neutral: true
      """.trimIndent()
    )
    writeJavaDependencies()

    val result = runner("compileJava").build()

    assertThat(result.task(":compileJava")?.outcome).isEqualTo(SUCCESS)
    val classes = File(directory, "build/classes/java/main").toURI().toURL()
    URLClassLoader(arrayOf(classes), null).use { loader ->
      val names = loader.loadClass("datadog.trace.api.KnownTags").fields
        .filter { it.type == String::class.java }.map { it.get(null) }
      assertThat(names).containsExactlyInAnyOrder("tag\"\\name", "alias\"\\name\nline")
    }
  }

  @Test
  fun `compilation reuses the configuration cache and regenerates after YAML changes`() {
    fixture("plugins { java; id(\"dd-trace-java.tag-registry-generator\") }")
    writeJavaDependencies()
    assertThat(runner("compileJava").build().task(":generateKnownTags")?.outcome)
      .isEqualTo(SUCCESS)

    val second = runner("compileJava").build()
    assertThat(second.output).contains("Reusing configuration cache.")
    assertThat(second.task(":generateKnownTags")?.outcome).isEqualTo(UP_TO_DATE)

    File(directory, "tag-conventions.yaml").writeText(
      "span_types: {base: {tags: [{dd-name: updated}]}}"
    )
    val third = runner("compileJava").build()
    assertThat(third.output).contains("Reusing configuration cache.")
    assertThat(third.task(":generateKnownTags")?.outcome).isEqualTo(SUCCESS)
    assertThat(third.task(":compileJava")?.outcome).isEqualTo(SUCCESS)
    assertThat(generated().readText()).contains("UPDATED_NAME").doesNotContain("FOO_NAME")
  }

  @Test
  fun `generation can be restored from cache in a different project directory`() {
    val firstProject = File(directory, "first")
    val secondProject = File(directory, "second")
    fixture(projectDir = firstProject)
    fixture(projectDir = secondProject)
    val cache = File(directory, "cache").toURI()
    val settings = "rootProject.name = \"fixture\"\nbuildCache { local { directory = uri(\"$cache\") } }"
    File(firstProject, "settings.gradle.kts").writeText(settings)
    File(secondProject, "settings.gradle.kts").writeText(settings)

    assertThat(
      runner("generateKnownTags", "--build-cache", projectDir = firstProject)
        .build().task(":generateKnownTags")?.outcome
    ).isEqualTo(SUCCESS)
    val result = runner("generateKnownTags", "--build-cache", projectDir = secondProject).build()

    assertThat(result.task(":generateKnownTags")?.outcome).isEqualTo(FROM_CACHE)
    assertThat(generated(secondProject).readText()).isEqualTo(generated(firstProject).readText())
  }

  @Test
  fun `output follows a custom build directory and clean removes it`() {
    fixture(
      """
      plugins { id("dd-trace-java.tag-registry-generator"); java }
      layout.buildDirectory.set(layout.projectDirectory.dir("custom-build"))
      """
    )
    writeJavaDependencies()

    val result = runner("compileJava").build()

    assertThat(result.task(":generateKnownTags")?.outcome).isEqualTo(SUCCESS)
    val output = File(directory, "custom-build/generated/tag-registry")
    assertThat(File(output, "java/datadog/trace/api/KnownTags.java")).exists()
    assertThat(File(directory, "build/generated")).doesNotExist()
    runner("clean").build()
    assertThat(output).doesNotExist()
  }

  @Test
  fun `input and output can be overridden without losing the producer dependency`() {
    fixture(
      """
      plugins { java; id("dd-trace-java.tag-registry-generator") }
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
    assertThat(File(directory, "build/custom-generated/java/datadog/trace/api/KnownTags.java")).exists()
    assertThat(File(directory, "build/classes/java/main/datadog/trace/api/KnownTags.class")).exists()
  }

  @Test
  fun `help does not read or generate the domain model`() {
    fixture()
    File(directory, "tag-conventions.yaml").delete()

    val result = runner("help").build()

    assertThat(result.task(":generateKnownTags")).isNull()
    assertThat(generated()).doesNotExist()
  }

  private fun fixture(
    build: String = "plugins { id(\"dd-trace-java.tag-registry-generator\") }",
    projectDir: File = directory
  ) {
    projectDir.mkdirs()
    File(projectDir, "settings.gradle.kts").writeText("rootProject.name = \"fixture\"")
    File(projectDir, "build.gradle.kts").writeText(
      build.trimIndent() + "\n" +
        "tagRegistry { tagConventionsFile.convention(layout.projectDirectory.file(\"tag-conventions.yaml\")) }\n"
    )
    File(projectDir, "tag-conventions.yaml").writeText(
      "span_types: {base: {tags: [{dd-name: foo}, {dd-name: foo.name}]}}"
    )
  }

  private fun runner(vararg arguments: String, projectDir: File = directory): GradleRunner = GradleRunner.create().withProjectDir(projectDir).withPluginClasspath()
    .withArguments(*arguments, "--configuration-cache", "--stacktrace")

  private fun generated(projectDir: File = directory): File = File(projectDir, "build/generated/tag-registry/java/datadog/trace/api/KnownTags.java")

  // Only the API surface is needed here; internal-api tests exercise the real resolver at runtime.
  private fun writeJavaDependencies() {
    val api = File(directory, "src/main/java/datadog/trace/api").apply { mkdirs() }
    File(api, "KnownTagCodec.java").writeText(
      """
      package datadog.trace.api;
      public final class KnownTagCodec {
        public interface Resolver {
          String nameOf(long id);
          String openTelemetryNameOf(long id);
          long keyOf(String name);
        }
        public static int serialNum(long id) { return (int) (id >>> 48); }
      }
      """.trimIndent()
    )
    val util = File(directory, "src/main/java/datadog/trace/util").apply { mkdirs() }
    File(util, "StringIndex.java").writeText(
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
      """.trimIndent()
    )
  }
}
