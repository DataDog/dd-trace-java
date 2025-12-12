package datadog.gradle.plugin.config

import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Paths

class ParseV2SupportedConfigurationsTest {
  @Test
  fun `should generate Java file from JSON configuration`(@TempDir projectDir: File) {
    val (buildResult, generatedFile) = runGradleTask(projectDir)

    assertEquals(TaskOutcome.SUCCESS, buildResult.task(":generateSupportedConfigurations")?.outcome)

    assertTrue(generatedFile.exists(), "Generated Java file should exist")

    val content = generatedFile.readText()
    assertTrue(content.contains("package datadog.test;"))
    assertTrue(content.contains("public final class TestGeneratedSupportedConfigurations {"))

    assertTrue(content.contains("public static final Map<String, List<SupportedConfiguration>> SUPPORTED;"))
    assertTrue(content.contains("public static final Map<String, List<String>> ALIASES;"))
    assertTrue(content.contains("public static final Map<String, String> ALIAS_MAPPING;"))
    assertTrue(content.contains("public static final Map<String, String> DEPRECATED;"))
    assertTrue(content.contains("public static final Map<String, String> REVERSE_PROPERTY_KEYS_MAP;"))

    assertTrue(content.contains("private static Map<String, List<SupportedConfiguration>> initSupported()"))
    assertTrue(content.contains("private static void initSupported1(Map<String, List<SupportedConfiguration>> supportedMap)"))
    assertTrue(content.contains("private static void initSupported2(Map<String, List<SupportedConfiguration>> supportedMap)"))
    assertTrue(content.contains("private static Map<String, List<String>> initAliases()"))
    assertTrue(content.contains("private static Map<String, String> initAliasMapping()"))
    assertTrue(content.contains("private static Map<String, String> initDeprecated()"))
    assertTrue(content.contains("private static Map<String, String> initReversePropertyKeysMap()"))

    assertContainsSupportedConfig(
      content,
      key = "DD_ACTION_EXECUTION_ID",
      version = "A",
      type = "string",
      default = "null",
      aliases = emptyList(),
      propertyKeys = listOf("property.key")
    )

    assertContainsSupportedConfig(
      content,
      key = "DD_AGENTLESS_LOG_SUBMISSION_ENABLED",
      version = "A",
      type = "boolean",
      default = "false",
      aliases = emptyList()
    )
    
    assertContainsSupportedConfig(
      content,
      key = "DD_AGENTLESS_LOG_SUBMISSION_ENABLED",
      version = "B",
      type = "boolean",
      default = "true",
      aliases = listOf("DD_ALIAS")
    )

    assertTrue(content.contains("""aliasesMap.put("DD_ACTION_EXECUTION_ID", Collections.unmodifiableList(Arrays.asList()))"""))
    assertTrue(content.contains("""aliasesMap.put("DD_AGENTLESS_LOG_SUBMISSION_ENABLED", Collections.unmodifiableList(Arrays.asList("DD_ALIAS")))"""))

    assertTrue(content.contains("""aliasMappingMap.put("DD_ALIAS", "DD_AGENTLESS_LOG_SUBMISSION_ENABLED")"""))

    assertTrue(content.contains("""deprecatedMap.put("old.config", "Use test.config instead")"""))
    assertTrue(content.contains("""deprecatedMap.put("legacy.setting", "No longer supported")"""))

    assertTrue(content.contains("""reversePropertyKeysMapping.put("property.key", "DD_ACTION_EXECUTION_ID")"""))
  }

  private fun runGradleTask(projectDir: File): Pair<BuildResult, File> {
    val jsonFile = file(projectDir, "test-supported-configurations.json")
    jsonFile.writeText(
      """
      {
        "supportedConfigurations": {
          "DD_ACTION_EXECUTION_ID": [
            {
              "version": "A",
              "type": "string",
              "default": null,
              "aliases": [],
              "propertyKeys": ["property.key"] 
            }
          ],
          "DD_AGENTLESS_LOG_SUBMISSION_ENABLED": [
            {
              "version": "A",
              "type": "boolean",
              "default": "false",
              "aliases": []
            },
            {
              "version": "B",
              "type": "boolean",
              "default": "true",
              "aliases": ["DD_ALIAS"]
            }
          ]
        },
        "deprecations": {
          "old.config": "Use test.config instead",
          "legacy.setting": "No longer supported"
        }
      }
      """.trimIndent()
    )

    setupGradleProject(projectDir)

    val buildResult = GradleRunner.create()
      .forwardOutput()
      .withPluginClasspath()
      .withArguments("generateSupportedConfigurations")
      .withProjectDir(projectDir)
      .build()

    val generatedFile = file(projectDir, "build", "generated", "supportedConfigurations", "datadog", "test", "TestGeneratedSupportedConfigurations.java")
    return Pair(buildResult, generatedFile)
  }

  private fun setupGradleProject(projectDir: File) {
    file(projectDir, "settings.gradle.kts").writeText(
      """
      rootProject.name = "test-config-project"
      """.trimIndent()
    )

    file(projectDir, "build.gradle.kts").writeText(
      """
      plugins {
        id("java")
        id("dd-trace-java.supported-config-generator")
      }
      
      group = "datadog.config.test"
      
      supportedTracerConfigurations {
        jsonFile.set(file("test-supported-configurations.json"))
        destinationDirectory.set(file("build/generated/supportedConfigurations"))
        className.set("datadog.test.TestGeneratedSupportedConfigurations")
      }
      """.trimIndent()
    )
  }

  private fun file(projectDir: File, vararg parts: String, makeDirectory: Boolean = false): File {
    val f = Paths.get(projectDir.absolutePath, *parts).toFile()

    if (makeDirectory) {
      f.parentFile.mkdirs()
    }

    return f
  }

  private fun assertContainsSupportedConfig(
    content: String,
    key: String,
    version: String,
    type: String,
    default: String,
    aliases: List<String>,
    propertyKeys: List<String> = emptyList()
  ) {
    val aliasesArray = aliases.joinToString(", ") { "\"$it\"" }
    val propertyKeysArray = propertyKeys.joinToString(", ") { "\"$it\"" }

    assertTrue(
      content.contains("""supportedMap.put("$key""""),
      "Should contain supportedMap.put for key: $key"
    )

    val expectedPattern = buildString {
      append("new SupportedConfiguration(")
      append("\"$version\", ")
      append("\"$type\", ")
      append(if (default == "null") "null" else "\"$default\"")
      append(", ")
      append("Arrays.asList($aliasesArray)")
      append(", ")
      append("Arrays.asList($propertyKeysArray)")
      append(")")
    }

    assertTrue(
      content.contains(expectedPattern),
      "Should contain SupportedConfiguration: $expectedPattern"
    )
  }
}
