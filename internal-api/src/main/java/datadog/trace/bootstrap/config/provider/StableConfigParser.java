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
      // TODO: Support multiple sets of rules + configs.
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
          // Use the first selector that matches; return early
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

  // TODO: Create strict types for origin and operator values
  private static boolean selectorMatch(
      String origin, List<String> matches, String operator, String key) {
    //    if(origin.equals("language")) {
    //      List<String> matchesList = Arrays.asList(matches);
    //      return matchesList.contains("Java") || matchesList.contains("java") &&
    // operator.equals("equals");
    //    }
    //    else if(origin.equals("tags")) {
    //
    //    } else if(origin.equals("environment_variables")) {
    //
    //    } else if(origin.equals("process_arguments")) {
    //
    //    }
    return true;
  }
}
