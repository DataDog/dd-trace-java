package datadog.generator;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class ParseSupportedConfigurations {

  public static void main(String[] args) {
    String supportedConfigurationsFilename =
        args[0]; // e.g., "resources/supported-configurations.json"
    String generatedMappingPath = args[1]; // e.g.,
    // "build/generated-sources/datadog/environment/GeneratedSupportedConfigurations.java"

    String jsonString;
    try {

      InputStream in =
          ParseSupportedConfigurations.class
              .getClassLoader()
              .getResourceAsStream(supportedConfigurationsFilename);
      if (in == null) {
        throw new IllegalArgumentException(
            "Resource not found: " + supportedConfigurationsFilename);
      }

      ObjectMapper mapper = new ObjectMapper();
      Map<String, Object> fileData = mapper.readValue(in, Map.class);

      Map<String, List<String>> supported =
          (Map<String, List<String>>) fileData.get("supportedConfigurations");
      Map<String, List<String>> aliases = (Map<String, List<String>>) fileData.get("aliases");
      Map<String, String> deprecated = (Map<String, String>) fileData.get("deprecations");

      Map<String, String> aliasMapping = new HashMap<>();
      for (Map.Entry<String, List<String>> entry : aliases.entrySet()) {
        for (String alias : entry.getValue()) {
          aliasMapping.put(alias, entry.getKey());
        }
      }
      generateJavaFile(generatedMappingPath, supported.keySet(), aliases, aliasMapping, deprecated);
    } catch (IOException e) {
      throw new RuntimeException("Failed to read " + supportedConfigurationsFilename, e);
    }
  }

  private static void generateJavaFile(
      String outputPath,
      Set<String> supported,
      Map<String, List<String>> aliases,
      Map<String, String> aliasMapping,
      Map<String, String> deprecated)
      throws IOException {
    try (PrintWriter out = new PrintWriter(Files.newBufferedWriter(Paths.get(outputPath)))) {
      out.println("package datadog.generator;");
      out.println();
      out.println("import java.util.*;");
      out.println();
      out.println("public final class GeneratedSupportedConfigurations {");

      // Supported set using Arrays.asList and HashSet
      out.println("  public static final Set<String> SUPPORTED;");
      out.println();

      // ALIASES map
      out.println("  public static final Map<String, List<String>> ALIASES;");
      out.println();

      // ALIAS_MAPPING map
      out.println("  public static final Map<String, String> ALIAS_MAPPING;");
      out.println();

      // DEPRECATED map
      out.println("  public static final Map<String, String> DEPRECATED;");
      out.println();

      // Static initializer block
      out.println("  static {");

      // Initialize SUPPORTED
      out.print("    Set<String> supportedSet = new HashSet<>(Arrays.asList(");
      Iterator<String> supportedIter = supported.iterator();
      while (supportedIter.hasNext()) {
        String key = supportedIter.next();
        out.print("\"" + key + "\"");
        if (supportedIter.hasNext()) {
          out.print(", ");
        }
      }
      out.println("));");
      out.println("    SUPPORTED = Collections.unmodifiableSet(supportedSet);");
      out.println();

      // Initialize ALIASES
      out.println("    Map<String, List<String>> aliasesMap = new HashMap<>();");
      for (Map.Entry<String, List<String>> entry : aliases.entrySet()) {
        out.printf(
            "    aliasesMap.put(\"%s\", Collections.unmodifiableList(Arrays.asList(%s)));\n",
            entry.getKey(), quoteList(entry.getValue()));
      }
      out.println("    ALIASES = Collections.unmodifiableMap(aliasesMap);");
      out.println();

      // Initialize ALIAS_MAPPING
      out.println("    Map<String, String> aliasMappingMap = new HashMap<>();");
      for (Map.Entry<String, String> entry : aliasMapping.entrySet()) {
        out.printf("    aliasMappingMap.put(\"%s\", \"%s\");\n", entry.getKey(), entry.getValue());
      }
      out.println("    ALIAS_MAPPING = Collections.unmodifiableMap(aliasMappingMap);");
      out.println();

      // Initialize DEPRECATED
      out.println("    Map<String, String> deprecatedMap = new HashMap<>();");
      for (Map.Entry<String, String> entry : deprecated.entrySet()) {
        out.printf("    deprecatedMap.put(\"%s\", \"%s\");\n", entry.getKey(), entry.getValue());
      }
      out.println("    DEPRECATED = Collections.unmodifiableMap(deprecatedMap);");

      out.println("  }"); // end static block
      out.println("}"); // end class
    }
  }

  private static String quoteList(List<String> list) {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < list.size(); i++) {
      sb.append("\"").append(list.get(i)).append("\"");
      if (i < list.size() - 1) {
        sb.append(", ");
      }
    }
    return sb.toString();
  }
}
