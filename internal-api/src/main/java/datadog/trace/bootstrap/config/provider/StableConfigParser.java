package datadog.trace.bootstrap.config.provider;

import datadog.trace.bootstrap.config.provider.stableconfigyaml.ConfigurationMap;
import datadog.trace.bootstrap.config.provider.stableconfigyaml.Rule;
import datadog.trace.bootstrap.config.provider.stableconfigyaml.Selector;
import datadog.trace.bootstrap.config.provider.stableconfigyaml.StableConfigYaml;
import datadog.yaml.YamlParser;
import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class StableConfigParser {
  private static final Logger log = LoggerFactory.getLogger(StableConfigParser.class);

  /**
   * Parses a configuration file and returns a stable configuration object.
   *
   * <p>This method reads a configuration file from the given file path, parses the YAML content,
   * and identifies configurations for the process using a combination of apm_configuration_default
   * and apm_configuration_rules. If a matching rule is found in apm_configuration_rules, it returns
   * a {@link StableConfigSource.StableConfig} object with the merged configuration. If no matching
   * rule is found, it returns the default configuration. If neither a matching rule nor a default
   * configuration is found, an empty configuration is returned.
   *
   * @param filePath The path to the YAML configuration file to be parsed.
   * @return A {@link StableConfigSource.StableConfig} object containing the stable configuration.
   * @throws IOException If there is an error reading the file or parsing the YAML content.
   */
  public static StableConfigSource.StableConfig parse(String filePath) throws IOException {
    try {
      StableConfigYaml data = YamlParser.parse(filePath, StableConfigYaml.class);

      String configId = data.getConfig_id();
      ConfigurationMap configMap = data.getApm_configuration_default();
      List<Rule> rules = data.getApm_configuration_rules();

      if (!rules.isEmpty()) {
        for (Rule rule : rules) {
          if (doesRuleMatch(rule)) {
            // Merge configs found in apm_configuration_default and apm_configuration_rules
            configMap.putAll(rule.getConfiguration());
            return createStableConfig(configId, configMap);
          }
        }
      }
      // If configs were found in apm_configuration_default, use them
      if (!configMap.isEmpty()) {
        return createStableConfig(configId, configMap);
      }

      // If there's a configId but no configMap, return an empty map
      if (configId != null) {
        return new StableConfigSource.StableConfig(configId, Collections.emptyMap());
      }

    } catch (IOException e) {
      // TODO: Update this log from "stable configuration" to the official name of the feature, once
      // determined
      log.debug(
          "Stable configuration file either not found or not readable at filepath {}", filePath);
    }
    return StableConfigSource.StableConfig.EMPTY;
  }

  /**
   * Checks if the rule's selectors match the current process. All must match for a "true" return
   * value.
   */
  private static boolean doesRuleMatch(Rule rule) {
    for (Selector selector : rule.getSelectors()) {
      if (!selectorMatch(
          selector.getOrigin(), selector.getMatches(), selector.getOperator(), selector.getKey())) {
        return false; // Return false immediately if any selector doesn't match
      }
    }
    return true; // Return true if all selectors match
  }

  /** Creates a StableConfig object from the provided configId and configMap. */
  private static StableConfigSource.StableConfig createStableConfig(
      String configId, ConfigurationMap configMap) {
    return new StableConfigSource.StableConfig(configId, new HashMap<>(configMap));
  }

  private static boolean validOperatorForLanguageOrigin(String operator) {
    operator = operator.toLowerCase();
    switch (operator) {
      case "equals":
      case "starts_with":
      case "ends_with":
      case "contains":
        return true;
      default:
        return false;
    }
  }

  // TODO: Make this private again after testing?
  // We do all of the case insensitivity modifications in this function, because each selector will
  // be viewed just once
  public static boolean selectorMatch(
      String origin, List<String> matches, String operator, String key) {
    switch (origin.toLowerCase()) {
      case "language":
        if (!validOperatorForLanguageOrigin(operator)) {
          return false;
        }
        for (String entry : matches) {
          // loose match on any reference to "*java*"
          if (entry.toLowerCase().contains("java")) {
            return true;
          }
        }
      case "environment_variables":
        String envValue = System.getenv(key.toUpperCase());
        if (envValue == null) {
          return false;
        }
        envValue = envValue.toLowerCase();
        switch (operator.toLowerCase()) {
          case "exists":
            // We don't care about the value
            return true;
          case "equals":
            for (String value : matches) {
              if (value.equalsIgnoreCase(envValue)) {
                return true;
              }
            }
            break;
          case "starts_with":
            for (String value : matches) {
              if (envValue.startsWith(value.toLowerCase())) {
                return true;
              }
            }
            break;
          case "ends_with":
            for (String value : matches) {
              if (envValue.endsWith(value.toLowerCase())) {
                return true;
              }
            }
            break;
          case "contains":
            for (String value : matches) {
              if (envValue.contains(value.toLowerCase())) {
                return true;
              }
            }
          default:
            return false;
        }
        return false;
      case "process_arguments":
        // TODO: use CLIHelper once merged
        return true;
      case "tags":
        // TODO: Support this down the line (Must define the source of "tags" first)
        return false;
      default:
        return false;
    }
  }
}
