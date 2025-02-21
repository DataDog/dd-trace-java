package datadog.trace.bootstrap.config.provider;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class StableConfigParser {
  private static final Logger log = LoggerFactory.getLogger(StableConfigParser.class);
  // Match config_id:<value>
  private static final Pattern idPattern = Pattern.compile("^config_id\\s*:(.*)$");
  // Match 'apm_configuration_default:'
  private static final Pattern apmConfigPattern = Pattern.compile("^apm_configuration_default:$");
  // Match indented (2 spaces) key-value pairs, either with double quotes or without
  private static final Pattern keyValPattern =
      Pattern.compile("^\\s{2}([^:]+):\\s*(\"[^\"]*\"|[^\"\\n]*)$");;

  public static StableConfigSource.StableConfig parse(String filePath) throws IOException {
    File file = new File(filePath);
    if (!file.exists()) {
      log.debug("Stable configuration file not available at specified path: {}", file);
      return StableConfigSource.StableConfig.EMPTY;
    }
    Map<String, String> configMap = new HashMap<>();
    String[] configId = new String[1];
    try (Stream<String> lines = Files.lines(Paths.get(filePath))) {
      int apmConfigNotFound = -1, apmConfigStarted = 0, apmConfigComplete = 1;
      int[] apmConfigFound = {apmConfigNotFound};
      lines.forEach(
          line -> {
            Matcher matcher = idPattern.matcher(line);
            if (matcher.find()) {
              // Do not allow duplicate config_id keys
              if (configId[0] != null) {
                throw new RuntimeException("Duplicate config_id keys found; file may be malformed");
              }
              configId[0] = trimQuotes(matcher.group(1).trim());
              return; // go to next line
            }
            // TODO: Do not allow duplicate apm_configuration_default keys; and/or return early once
            // apmConfigFound[0] == apmConfigComplete
            if (apmConfigFound[0] == apmConfigNotFound
                && apmConfigPattern.matcher(line).matches()) {
              apmConfigFound[0] = apmConfigStarted;
              return; // go to next line
            }
            if (apmConfigFound[0] == apmConfigStarted) {
              Matcher keyValueMatcher = keyValPattern.matcher(line);
              if (keyValueMatcher.matches()) {
                configMap.put(
                    keyValueMatcher.group(1).trim(),
                    trimQuotes(keyValueMatcher.group(2).trim())); // Store key-value pair in map
              } else {
                // If we encounter a non-indented or non-key-value line, stop processing
                apmConfigFound[0] = apmConfigComplete;
              }
            }
          });
      return new StableConfigSource.StableConfig(configId[0], configMap);
    }
  }

  private static String trimQuotes(String value) {
    if (value.length() > 1 && (value.startsWith("'") && value.endsWith("'"))
        || (value.startsWith("\"") && value.endsWith("\""))) {
      return value.substring(1, value.length() - 1);
    }
    return value;
  }
}
