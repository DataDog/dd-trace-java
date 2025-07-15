package datadog.environment;

import datadog.json.JsonMapper;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// TODO: Make this class run at buildtime and generate a static class with the data
public class ParseSupportedConfigurations {
  public static Map<String, Object> fileData;
  public static Map<String, String> aliasMapping;
  public static Set<String> supportedConfigurations;
  public static Map<String, String> deprecatedConfigurations;
  public static Map<String, List<String>> aliases;
  private static final Logger log = LoggerFactory.getLogger(ParseSupportedConfigurations.class);

  public static void loadSupportedConfigurations(String filename) {
    String jsonString;
    try {
      InputStream in =
          ParseSupportedConfigurations.class.getClassLoader().getResourceAsStream(filename);
      jsonString = new Scanner(in, "UTF-8").useDelimiter("\\A").next();
      fileData = JsonMapper.fromJsonToMap(jsonString);
      supportedConfigurations =
          new HashSet<>(
              ((Map<String, List<String>>) fileData.get("supportedConfigurations")).keySet());
      aliasToConfig();
      deprecatedConfigurations = (Map<String, String>) fileData.get("deprecated");
      aliases = (Map<String, List<String>>) fileData.get("aliases");
    } catch (IOException e) {
      throw new RuntimeException("Failed to read " + filename, e);
    }
  }

  private static void aliasToConfig() {
    aliasMapping = new HashMap<>();
    Map<String, List<String>> aliases = (Map<String, List<String>>) fileData.get("aliases");
    for (String env : aliases.keySet()) {
      for (String alias : aliases.get(env)) {
        if (aliasMapping.containsKey(alias)) {
          log.info(
              "{} is listed as an alias of {} when it already exists as an alias of {}",
              alias,
              env,
              aliasMapping.get(alias));
        } else {
          aliasMapping.put(alias, env);
        }
      }
    }
  }
}
