package datadog.gradle.plugin.config

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import org.gradle.api.DefaultTask
import org.gradle.api.model.ObjectFactory
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.io.File
import java.io.FileInputStream
import java.io.PrintWriter
import javax.inject.Inject

@CacheableTask
abstract class ParseSupportedConfigurationsTask @Inject constructor(
  private val objects: ObjectFactory
) : DefaultTask() {
  @InputFile
  @PathSensitive(PathSensitivity.NONE)
  val jsonFile = objects.fileProperty()

  @get:OutputDirectory
  val destinationDirectory = objects.directoryProperty()

  @Input
  val className = objects.property(String::class.java)

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

    @Suppress("UNCHECKED_CAST")
    val supported = fileData["supportedConfigurations"] as Map<String, List<String>>
    @Suppress("UNCHECKED_CAST")
    val aliases = fileData["aliases"] as Map<String, List<String>>
    @Suppress("UNCHECKED_CAST")
    val deprecated = (fileData["deprecations"] as? Map<String, String>) ?: emptyMap()

    val aliasMapping = mutableMapOf<String, String>()
    for ((canonical, alist) in aliases) {
      for (alias in alist) aliasMapping[alias] = canonical
    }

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
      supported.keys,
      aliases,
      aliasMapping,
      deprecated
    )
  }

  private fun generateJavaFile(
    outputPath: String,
    className: String,
    packageName: String,
    supportedKeys: Set<String>,
    aliases: Map<String, List<String>>,
    aliasMapping: Map<String, String>,
    deprecated: Map<String, String>
  ) {
    val outFile = File(outputPath)
    outFile.parentFile?.mkdirs()

    PrintWriter(outFile).use { out ->
      // NOTE: adjust these if you want to match task's className
      out.println("package $packageName;")
      out.println()
      out.println("import java.util.*;")
      out.println()
      out.println("public final class $className {")
      out.println()
      out.println("  public static final Set<String> SUPPORTED;")
      out.println()
      out.println("  public static final Map<String, List<String>> ALIASES;")
      out.println()
      out.println("  public static final Map<String, String> ALIAS_MAPPING;")
      out.println()
      out.println("  public static final Map<String, String> DEPRECATED;")
      out.println()
      out.println("  static {")
      out.println()

      // SUPPORTED
      out.print("    Set<String> supportedSet = new HashSet<>(Arrays.asList(")
      val supportedIter = supportedKeys.toSortedSet().iterator()
      while (supportedIter.hasNext()) {
        val key = supportedIter.next()
        out.print("\"${esc(key)}\"")
        if (supportedIter.hasNext()) out.print(", ")
      }
      out.println("));")
      out.println("    SUPPORTED = Collections.unmodifiableSet(supportedSet);")
      out.println()

      // ALIASES
      out.println("    Map<String, List<String>> aliasesMap = new HashMap<>();")
      for ((canonical, list) in aliases.toSortedMap()) {
        out.printf(
          "    aliasesMap.put(\"%s\", Collections.unmodifiableList(Arrays.asList(%s)));\n",
          esc(canonical),
          quoteList(list)
        )
      }
      out.println("    ALIASES = Collections.unmodifiableMap(aliasesMap);")
      out.println()

      // ALIAS_MAPPING
      out.println("    Map<String, String> aliasMappingMap = new HashMap<>();")
      for ((alias, target) in aliasMapping.toSortedMap()) {
        out.printf("    aliasMappingMap.put(\"%s\", \"%s\");\n", esc(alias), esc(target))
      }
      out.println("    ALIAS_MAPPING = Collections.unmodifiableMap(aliasMappingMap);")
      out.println()

      // DEPRECATED
      out.println("    Map<String, String> deprecatedMap = new HashMap<>();")
      for ((oldKey, note) in deprecated.toSortedMap()) {
        out.printf("    deprecatedMap.put(\"%s\", \"%s\");\n", esc(oldKey), esc(note))
      }
      out.println("    DEPRECATED = Collections.unmodifiableMap(deprecatedMap);")
      out.println()
      out.println("  }")
      out.println("}")
    }
  }

  private fun quoteList(list: List<String>): String =
    list.joinToString(", ") { "\"${esc(it)}\"" }

  private fun esc(s: String): String =
    s.replace("\\", "\\\\").replace("\"", "\\\"")
}
