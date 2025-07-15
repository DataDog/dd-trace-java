package datadog.environment;

import de.thetaphi.forbiddenapis.SuppressForbidden;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@SuppressForbidden
public class ConfigHelper {
  // Parse the supported-configurations JSON once using ParseSupportedConfigurations.

  public static Map<String, String> getEnvironmentVariables() {
    // TODO: Remove once JSON parsing is separate from data access
    ParseSupportedConfigurations.loadSupportedConfigurations("supported-configurations.json");

    Map<String, String> env = System.getenv();
    Map<String, String> configs = new LinkedHashMap<>();
    for (Map.Entry<String, String> entry : env.entrySet()) {
      String key = entry.getKey();
      String value = entry.getValue();
      if (key.startsWith("DD_")
          || key.startsWith("OTEL_")
          || ParseSupportedConfigurations.aliasMapping.containsKey(key)) {
        if (ParseSupportedConfigurations.supportedConfigurations.contains(key)) {
          configs.put(key, value);
          // If this environment variable is the alias of another, and we haven't processed the
          // original environment variable yet, handle it here.
        } else if (ParseSupportedConfigurations.aliasMapping.containsKey(key)
            && !configs.containsKey(ParseSupportedConfigurations.aliasMapping.get(key))) {
          List<String> aliasList =
              ParseSupportedConfigurations.aliases.get(
                  ParseSupportedConfigurations.aliasMapping.get(key));
          for (String alias : aliasList) {
            if (env.containsKey(alias)) {
              configs.put(ParseSupportedConfigurations.aliasMapping.get(key), env.get(alias));
              break;
            }
          }
        }
        //        if (deprecatedConfigurations.containsKey(key)) {
        //          String warning = "Environment variable " + key + " is deprecated. " +
        //              (aliasMapping.containsKey(key)
        //                  ? "Please use " + aliasMapping.get(key) + " instead."
        //                  : deprecatedConfigurations.get(key));
        //          System.err.println(warning); // TODO: REPLACE with log call
        //        }
      } else {
        configs.put(key, value);
      }
    }
    return configs;
  }

  public static String getEnvironmentVariable(String name) {
    // TODO: Remove once JSON parsing is separate from data access
    ParseSupportedConfigurations.loadSupportedConfigurations("supported-configurations.json");

    if ((name.startsWith("DD_")
            || name.startsWith("OTEL_")
            || ParseSupportedConfigurations.aliasMapping.containsKey(name))
        && !ParseSupportedConfigurations.supportedConfigurations.contains(name)) {
      throw new IllegalArgumentException(
          "Missing " + name + " env/configuration in supported-configurations.json file.");
    }
    String config = System.getenv(name);
    if (config == null && ParseSupportedConfigurations.aliases.containsKey(name)) {
      for (String alias : ParseSupportedConfigurations.aliases.get(name)) {
        String aliasValue = System.getenv(alias);
        if (aliasValue != null) {
          return aliasValue;
        }
      }
    }
    return config;
  }
}
