package datadog.gradle.plugin.config

import org.gradle.api.DefaultTask
import org.gradle.api.model.ObjectFactory
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.kotlin.dsl.property
import java.io.File
import java.io.FileInputStream
import java.io.PrintWriter
import javax.inject.Inject

@CacheableTask
abstract class ParseV2SupportedConfigurationsTask  @Inject constructor(
  private val objects: ObjectFactory
) : DefaultTask() {
  @InputFile
  @PathSensitive(PathSensitivity.NONE)
  val jsonFile = objects.fileProperty()

  @get:OutputDirectory
  val destinationDirectory = objects.directoryProperty()

  @Input
  val className = objects.property<String>()

  @TaskAction
  fun generate() {
    val input = jsonFile.get().asFile
    val outputDir = destinationDirectory.get().asFile
    val finalClassName = className.get()
    outputDir.mkdirs()

    // Read JSON (directly from the file, not classpath)
    val mapper = ObjectMapper()
    val fileData: Map<String, Any?> = FileInputStream(input).use { inStream ->
      mapper.readValue(inStream, object : TypeReference<Map<String, Any?>>() {})
    }

    // Fetch top-level keys of JSON file
    @Suppress("UNCHECKED_CAST")
    val supportedRaw = fileData["supportedConfigurations"] as Map<String, List<Map<String, Any?>>>
    @Suppress("UNCHECKED_CAST")
    val deprecated = (fileData["deprecations"] as? Map<String, String>) ?: emptyMap()

    // Parse supportedConfigurations key to into a V2 format
    val supported: Map<String, List<SupportedConfigurationItem>> = supportedRaw.mapValues { (_, configList) ->
      configList.map { configMap ->
        @Suppress("UNCHECKED_CAST")
        SupportedConfigurationItem(
          configMap["version"] as? String,
          configMap["type"] as? String,
          configMap["default"] as? String,
          (configMap["aliases"] as? List<String>) ?: emptyList(),
          (configMap["propertyKeys"] as? List<String>) ?: emptyList()
        )
      }
    }

    // Generate top-level mapping from config -> list of aliases and reverse alias mapping from alias -> top-level config
    // Note: This top-level alias mapping will be deprecated once Config Registry is mature enough to understand which version of a config a customer is using
    val aliases: Map<String, List<String>> = supported.mapValues { (_, configList) ->
      configList.flatMap { it.aliases }.distinct()
    }

    val aliasMapping = mutableMapOf<String, String>()
    for ((canonical, alist) in aliases) {
      for (alias in alist) aliasMapping[alias] = canonical
    }

    val reversePropertyKeysMap: Map<String, String> = supported.flatMap { (canonical, configList) ->
      configList.flatMap { config ->
        config.propertyKeys.map { propertyKey -> propertyKey to canonical }
      }
    }.toMap()

    // Build the output .java path from the fully-qualified class name
    val pkgName = finalClassName.substringBeforeLast('.', "")
    val pkgPath = pkgName.replace('.', File.separatorChar)
    val simpleName = finalClassName.substringAfterLast('.')
    val pkgDir = if (pkgPath.isEmpty()) outputDir else File(outputDir, pkgPath).also { it.mkdirs() }
    val generatedFile = File(pkgDir, "$simpleName.java").absolutePath

    // Call your existing generator (same signature as in your Java code)
    generateJavaFile(
      generatedFile,
      simpleName,
      pkgName,
      supported,
      aliases,
      aliasMapping,
      deprecated,
      reversePropertyKeysMap
    )
  }

  private fun generateJavaFile(
    outputPath: String,
    className: String,
    packageName: String,
    supported: Map<String, List<SupportedConfigurationItem>>,
    aliases: Map<String, List<String>>,
    aliasMapping: Map<String, String>,
    deprecated: Map<String, String>,
    reversePropertyKeysMap: Map<String, String>
  ) {
    val outFile = File(outputPath)
    outFile.parentFile?.mkdirs()

    PrintWriter(outFile).use { out ->
      out.println("package $packageName;")
      out.println()
      out.println("import java.util.*;")
      out.println()
      out.println("public final class $className {")
      out.println()
      out.println("  public static final Map<String, List<SupportedConfiguration>> SUPPORTED;")
      out.println()
      out.println("  public static final Map<String, List<String>> ALIASES;")
      out.println()
      out.println("  public static final Map<String, String> ALIAS_MAPPING;")
      out.println()
      out.println("  public static final Map<String, String> DEPRECATED;")
      out.println()
      out.println("  public static final Map<String, String> REVERSE_PROPERTY_KEYS_MAP;")
      out.println()
      out.println("  static {")
      out.println("    SUPPORTED = initSupported();")
      out.println("    ALIASES = initAliases();")
      out.println("    ALIAS_MAPPING = initAliasMapping();")
      out.println("    DEPRECATED = initDeprecated();")
      out.println("    REVERSE_PROPERTY_KEYS_MAP = initReversePropertyKeysMap();")
      out.println("  }")
      out.println()

      // initSupported() - split into two helper functions to avoid "code too large" error
      out.println("  private static Map<String, List<SupportedConfiguration>> initSupported() {")
      out.println("    Map<String, List<SupportedConfiguration>> supportedMap = new HashMap<>();")
      out.println("    initSupported1(supportedMap);")
      out.println("    initSupported2(supportedMap);")
      out.println("    return Collections.unmodifiableMap(supportedMap);")
      out.println("  }")
      out.println()

      val sortedSupported = supported.toSortedMap().entries.toList()
      val midpoint = sortedSupported.size / 2

      // initSupported1() - first half
      out.println("  private static void initSupported1(Map<String, List<SupportedConfiguration>> supportedMap) {")
      for ((key, configList) in sortedSupported.take(midpoint)) {
        out.print("    supportedMap.put(\"${esc(key)}\", Collections.unmodifiableList(Arrays.asList(")
        val configIter = configList.iterator()
        while (configIter.hasNext()) {
          val config = configIter.next()
          out.print("new SupportedConfiguration(")
          out.print("${escNullableString(config.version)}, ")
          out.print("${escNullableString(config.type)}, ")
          out.print("${escNullableString(config.default)}, ")
          out.print("Arrays.asList(${quoteList(config.aliases)}), ")
          out.print("Arrays.asList(${quoteList(config.propertyKeys)})")
          out.print(")")
          if (configIter.hasNext()) out.print(", ")
        }
        out.println(")));")
      }
      out.println("  }")
      out.println()

      // initSupported2() - second half
      out.println("  private static void initSupported2(Map<String, List<SupportedConfiguration>> supportedMap) {")
      for ((key, configList) in sortedSupported.drop(midpoint)) {
        out.print("    supportedMap.put(\"${esc(key)}\", Collections.unmodifiableList(Arrays.asList(")
        val configIter = configList.iterator()
        while (configIter.hasNext()) {
          val config = configIter.next()
          out.print("new SupportedConfiguration(")
          out.print("${escNullableString(config.version)}, ")
          out.print("${escNullableString(config.type)}, ")
          out.print("${escNullableString(config.default)}, ")
          out.print("Arrays.asList(${quoteList(config.aliases)}), ")
          out.print("Arrays.asList(${quoteList(config.propertyKeys)})")
          out.print(")")
          if (configIter.hasNext()) out.print(", ")
        }
        out.println(")));")
      }
      out.println("  }")
      out.println()

      // initAliases()
      out.println("  // Note: This top-level alias mapping will be deprecated once Config Registry is mature enough to understand which version of a config a customer is using")
      out.println("  private static Map<String, List<String>> initAliases() {")
      out.println("    Map<String, List<String>> aliasesMap = new HashMap<>();")
      for ((canonical, list) in aliases.toSortedMap()) {
        out.printf(
          "    aliasesMap.put(\"%s\", Collections.unmodifiableList(Arrays.asList(%s)));\n",
          esc(canonical),
          quoteList(list)
        )
      }
      out.println("    return Collections.unmodifiableMap(aliasesMap);")
      out.println("  }")
      out.println()

      // initAliasMapping()
      out.println("  private static Map<String, String> initAliasMapping() {")
      out.println("    Map<String, String> aliasMappingMap = new HashMap<>();")
      for ((alias, target) in aliasMapping.toSortedMap()) {
        out.printf("    aliasMappingMap.put(\"%s\", \"%s\");\n", esc(alias), esc(target))
      }
      out.println("    return Collections.unmodifiableMap(aliasMappingMap);")
      out.println("  }")
      out.println()

      // initDeprecated()
      out.println("  private static Map<String, String> initDeprecated() {")
      out.println("    Map<String, String> deprecatedMap = new HashMap<>();")
      for ((oldKey, note) in deprecated.toSortedMap()) {
        out.printf("    deprecatedMap.put(\"%s\", \"%s\");\n", esc(oldKey), esc(note))
      }
      out.println("    return Collections.unmodifiableMap(deprecatedMap);")
      out.println("  }")
      out.println()

      // initReversePropertyKeysMap()
      out.println("  private static Map<String, String> initReversePropertyKeysMap() {")
      out.println("    Map<String, String> reversePropertyKeysMapping = new HashMap<>();")
      for ((propertyKey, config) in reversePropertyKeysMap.toSortedMap()) {
        out.printf("    reversePropertyKeysMapping.put(\"%s\", \"%s\");\n", esc(propertyKey), esc(config))
      }
      out.println("    return Collections.unmodifiableMap(reversePropertyKeysMapping);")
      out.println("  }")
      out.println("}")
    }
  }

  private fun quoteList(list: List<String>): String =
    list.joinToString(", ") { "\"${esc(it)}\"" }

  private fun esc(s: String): String =
    s.replace("\\", "\\\\").replace("\"", "\\\"")

  private fun escNullableString(s: String?): String =
    if (s == null) "null" else "\"${esc(s)}\""
}

private data class SupportedConfigurationItem(
  val version: String?,
  val type: String?,
  val default: String?,
  val aliases: List<String>,
  val propertyKeys: List<String>
)
