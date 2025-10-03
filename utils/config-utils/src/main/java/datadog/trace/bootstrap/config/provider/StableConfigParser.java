package datadog.trace.bootstrap.config.provider;

import datadog.environment.SystemProperties;
import datadog.trace.bootstrap.config.provider.stableconfig.Rule;
import datadog.trace.bootstrap.config.provider.stableconfig.Selector;
import datadog.trace.bootstrap.config.provider.stableconfig.StableConfig;
import datadog.trace.config.inversion.ConfigHelper;
import datadog.yaml.YamlParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class StableConfigParser {
  private static final Logger log = LoggerFactory.getLogger(StableConfigParser.class);

  private static final String ENVIRONMENT_VARIABLES_PREFIX = "environment_variables['";
  private static final String PROCESS_ARGUMENTS_PREFIX = "process_arguments['";
  static final int MAX_FILE_SIZE_BYTES = 256 * 1024; // 256 KB in bytes;
  private static final String UNDEFINED_VALUE = "";

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
      Path path = Paths.get(filePath);

      // If file is over size limit, drop
      if (Files.size(path) > MAX_FILE_SIZE_BYTES) {
        log.warn(
            "Configuration file {} exceeds max size {} bytes; dropping.",
            filePath,
            MAX_FILE_SIZE_BYTES);
        return StableConfigSource.StableConfig.EMPTY;
      }

      String content = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);

      String processedContent = processTemplate(content);
      Object parsedYaml = YamlParser.parse(processedContent);
      StableConfig data = new StableConfig(parsedYaml);

      String configId = data.getConfigId();
      Map<String, Object> configMap = data.getApmConfigurationDefault();
      List<Rule> rules = data.getApmConfigurationRules();

      if (!rules.isEmpty()) {
        for (Rule rule : rules) {
          // Use the first matching rule
          if (doesRuleMatch(rule)) {
            // Merge configs found in apm_configuration_rules with those found in
            // apm_configuration_default
            Map<String, Object> mergedConfigMap = new LinkedHashMap<>(configMap);
            mergedConfigMap.putAll(rule.getConfiguration());
            return new StableConfigSource.StableConfig(configId, mergedConfigMap);
          }
        }
        log.debug("No matching rule found in stable configuration file {}", filePath);
      }
      // If configs were found in apm_configuration_default, use them
      if (!configMap.isEmpty()) {
        return new StableConfigSource.StableConfig(configId, configMap);
      }

      // If there's a configId but no configMap, use configId but return an empty map
      if (configId != null) {
        return new StableConfigSource.StableConfig(configId, Collections.emptyMap());
      }
    } catch (IOException e) {
      log.debug("Failed to read the stable configuration file: {}", filePath, e);
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

  private static boolean matchOperator(String value, String operator, List<String> matches) {
    if (value == null || operator == null) {
      return false;
    }
    if ("exists".equals(operator)) {
      return true;
    }
    if (matches == null || matches.isEmpty()) {
      return false;
    }
    value = value.toLowerCase(Locale.ROOT);

    Predicate<String> comparator;
    switch (operator) {
      case "equals":
        comparator = value::equals;
        break;
      case "starts_with":
        comparator = value::startsWith;
        break;
      case "ends_with":
        comparator = value::endsWith;
        break;
      case "contains":
        comparator = value::contains;
        break;
      default:
        return false;
    }

    for (String match : matches) {
      if (match == null) {
        continue;
      }
      match = match.toLowerCase(Locale.ROOT);
      if (comparator.test(match)) {
        return true;
      }
    }
    return false;
  }

  // We do all the case insensitivity modifications in this function, because each selector will
  // be viewed just once
  static boolean selectorMatch(String origin, List<String> matches, String operator, String key) {
    if (operator == null) {
      return false;
    }
    operator = operator.toLowerCase(Locale.ROOT);
    switch (origin.toLowerCase(Locale.ROOT)) {
      case "language":
        if ("exists".equals(operator)) {
          return false;
        }
        return matchOperator("java", operator, matches);
      case "environment_variables":
        if (key == null) {
          return false;
        }
        String envValue = ConfigHelper.env(key.toUpperCase(Locale.ROOT));
        return matchOperator(envValue, operator, matches);
      case "process_arguments":
        if (key == null) {
          return false;
        }
        // TODO: flesh out the meaning of each operator for process_arguments
        if (!key.startsWith("-D")) {
          log.warn(
              "Ignoring unsupported process_arguments entry in selector match, '{}'. Only system properties specified with the '-D' prefix are supported.",
              key);
          return false;
        }
        String argValue = SystemProperties.get(key.substring(2));
        return matchOperator(argValue, operator, matches);
      case "tags":
        // TODO: Support this down the line (Must define the source of "tags" first)
        return false;
      default:
        return false;
    }
  }

  static String processTemplate(String content) throws IOException {
    // Do nothing if there are no variables to process
    int openIndex = content.indexOf("{{");
    if (openIndex == -1) {
      return content;
    }

    StringBuilder result = new StringBuilder(content.length());

    // Add everything before the opening braces
    result.append(content, 0, openIndex);

    while (true) {

      // Find the closing braces
      int closeIndex = content.indexOf("}}", openIndex);
      if (closeIndex == -1) {
        throw new IOException("Unterminated template in config");
      }

      // Extract the template variable
      String templateVar = content.substring(openIndex + 2, closeIndex).trim();

      // Process the template variable and get its value
      String value = processTemplateVar(templateVar);

      // Add the processed value
      result.append(value);

      // Continue with the next template variable
      openIndex = content.indexOf("{{", closeIndex);
      if (openIndex == -1) {
        // Stop and add everything left after the final closing braces
        result.append(content, closeIndex + 2, content.length());
        break;
      } else {
        // Add everything between the last braces and the next
        result.append(content, closeIndex + 2, openIndex);
      }
    }

    return result.toString();
  }

  private static String processTemplateVar(String templateVar) throws IOException {
    if (templateVar.startsWith(ENVIRONMENT_VARIABLES_PREFIX) && templateVar.endsWith("']")) {
      String envVar =
          templateVar
              .substring(ENVIRONMENT_VARIABLES_PREFIX.length(), templateVar.length() - 2)
              .trim();
      if (envVar.isEmpty()) {
        throw new IOException("Empty environment variable name in template");
      }
      String value = ConfigHelper.env(envVar.toUpperCase(Locale.ROOT));
      if (value == null || value.isEmpty()) {
        return UNDEFINED_VALUE;
      }
      return value;
    } else if (templateVar.startsWith(PROCESS_ARGUMENTS_PREFIX) && templateVar.endsWith("']")) {
      String processArg =
          templateVar.substring(PROCESS_ARGUMENTS_PREFIX.length(), templateVar.length() - 2).trim();
      if (processArg.isEmpty()) {
        throw new IOException("Empty process argument in template");
      }
      if (!processArg.startsWith("-D")) {
        log.warn(
            "Ignoring unsupported process_arguments entry in template variable, '{}'. Only system properties specified with the '-D' prefix are supported.",
            processArg);
        return UNDEFINED_VALUE;
      }
      String value = SystemProperties.get(processArg.substring(2));
      if (value == null || value.isEmpty()) {
        return UNDEFINED_VALUE;
      }
      return value;
    } else {
      return UNDEFINED_VALUE;
    }
  }
}
