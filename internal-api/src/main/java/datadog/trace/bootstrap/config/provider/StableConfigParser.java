package datadog.trace.bootstrap.config.provider;

import datadog.trace.bootstrap.config.provider.StableConfigYaml.Rule;
import datadog.trace.bootstrap.config.provider.StableConfigYaml.Selector;
import datadog.trace.bootstrap.config.provider.StableConfigYaml.StableConfigYaml;
import datadog.yaml.YamlParser;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class StableConfigParser {
  private static final Logger log = LoggerFactory.getLogger(StableConfigParser.class);

  public static StableConfigSource.StableConfig parse(String filePath) throws IOException {
    try {
      StableConfigYaml data = YamlParser.parse(filePath, StableConfigYaml.class);
      ConfigurationMap configMap = data.getApm_configuration_default();
      List<Rule> rules = data.getApm_configuration_rules();
      if (rules != null) {
        for (Rule rule : rules) {
          List<Selector> selectors = rule.getSelectors();
          boolean match = true;
          for (Selector selector : selectors) {
            if (!selectorMatch(
                selector.getOrigin(),
                selector.getMatches(),
                selector.getOperator(),
                selector.getKey())) {
              match = false;
              break;
            }
          }
          // Use the first rule that matches; return early
          if (match) {
            configMap.putAll(rule.getConfiguration());
            return new StableConfigSource.StableConfig(
                data.getConfig_id(), new HashMap<>(configMap));
          }
        }
      }
      // If configs were found in apm_configuration_default, use them
      if (!configMap.isEmpty()) {
        return new StableConfigSource.StableConfig(data.getConfig_id(), new HashMap<>(configMap));
      }
    } catch (IOException e) {
      // TODO: Update this log from "stable configuration" to the official name of the feature, once
      // determined
      log.debug(
          "Stable configuration file either not found or not readable at filepath {}", filePath);
    }
    return StableConfigSource.StableConfig.EMPTY;
  }

  // TODO: Make this private again after testing
  public static boolean selectorMatch(
      String origin, List<String> matches, String operator, String key) {
    switch (origin) {
      case "language":
        return matches.contains("Java") || matches.contains("java") && operator.equals("equals");
      case "environment_variables":
        String envValue = System.getenv(key);
        if (envValue == null) {
          return false;
        }
        switch (operator) {
          case "exists":
            // We don't care about the value
            return true;
            // TODO: Determine if substrings are case insensitive
          case "equals":
            for (String value : matches) {
              if (value.equals(envValue)) {
                return true;
              }
            }
            break;
          case "starts_with":
            for (String value : matches) {
              if (envValue.startsWith(value)) {
                return true;
              }
            }
            break;
          case "ends_with":
            for (String value : matches) {
              if (envValue.endsWith(value)) {
                return true;
              }
            }
            break;
          case "contains":
            for (String value : matches) {
              if (envValue.contains(value)) {
                return true;
              }
            }
        }
        return false;
      case "process_arguments":
        // TODO: export getVMArgumentsThroughReflection to a utility class and cache its results, so
        // as not to re-run this query
        break;
    }
    //    else if(origin.equals("tags")) {
    //
    //    } else if(origin.equals("process_arguments")) {
    //
    //    }
    return true;
  }
}
