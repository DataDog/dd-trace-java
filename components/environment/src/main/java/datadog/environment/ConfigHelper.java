package datadog.environment;

import de.thetaphi.forbiddenapis.SuppressForbidden;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@SuppressForbidden
public class ConfigHelper {
  private static boolean configInversionStrict;

  public static void setConfigInversionStrict(boolean configInversionStrict) {
    ConfigHelper.configInversionStrict = configInversionStrict;
  }

  public static boolean isConfigInversionStrict() {
    return configInversionStrict;
  }

  public static Map<String, String> getEnvironmentVariables() {
    Map<String, String> env = System.getenv();
    Map<String, String> configs = new LinkedHashMap<>();
    for (Map.Entry<String, String> entry : env.entrySet()) {
      String key = entry.getKey();
      String value = entry.getValue();
      if (key.startsWith("DD_")
          || key.startsWith("OTEL_")
          || GeneratedSupportedConfigurations.ALIAS_MAPPING.containsKey(key)) {
        if (GeneratedSupportedConfigurations.SUPPORTED.contains(key)) {
          configs.put(key, value);
          // If this environment variable is the alias of another, and we haven't processed the
          // original environment variable yet, handle it here.
        } else if (GeneratedSupportedConfigurations.ALIAS_MAPPING.containsKey(key)
            && !configs.containsKey(GeneratedSupportedConfigurations.ALIAS_MAPPING.get(key))) {
          List<String> aliasList =
              GeneratedSupportedConfigurations.ALIASES.get(
                  GeneratedSupportedConfigurations.ALIAS_MAPPING.get(key));
          for (String alias : aliasList) {
            if (env.containsKey(alias)) {
              configs.put(GeneratedSupportedConfigurations.ALIAS_MAPPING.get(key), env.get(alias));
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
    if ((name.startsWith("DD_") || name.startsWith("OTEL_"))
        && !GeneratedSupportedConfigurations.ALIAS_MAPPING.containsKey(name)
        && !GeneratedSupportedConfigurations.SUPPORTED.contains(name)
        && configInversionStrict) {
      System.err.println(
          "Warning: Missing environment variable " + name + " from supported-configurations.json.");
    }

    String config = EnvironmentVariables.get(name);
    if (config == null && GeneratedSupportedConfigurations.ALIASES.containsKey(name)) {
      for (String alias : GeneratedSupportedConfigurations.ALIASES.get(name)) {
        String aliasValue = EnvironmentVariables.get(alias);
        if (aliasValue != null) {
          return aliasValue;
        }
      }
    }
    return config;
  }
}
