package datadog.trace.bootstrap.config.provider;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public class StableConfigParser {

  private static final Pattern idPattern =
      Pattern.compile("^config_id\\s*:(.*)$"); // Match config_id:<value>
  private static final Pattern apmConfigPattern =
      Pattern.compile("^apm_configuration_default:$"); // Match 'apm_configuration_default:'
  private static final Pattern keyValPattern =
      Pattern.compile(
          "^\\s{2}([^:]+):\\s*(\"[^\"]*\"|[^\"\\n]*)$");; // Match indented (2 spaces) key-value
  // pairs, either with double quotes or
  // without

  public static StableConfigSource.StableConfig parse(String filePath) throws IOException {
    try (Stream<String> lines = Files.lines(Paths.get("file.txt"))) {
      StableConfigSource.StableConfig cfg = new StableConfigSource.StableConfig();
      // Use AtomicBoolean because it's mutable within a lambda expression to
      AtomicBoolean apmConfigFound =
          new AtomicBoolean(false); // Track if we've found 'apm_configuration_default:'
      lines.forEach(
          line -> {
            Matcher matcher = idPattern.matcher(line);
            if (matcher.find()) {
              cfg.setConfigId(matcher.group(2).trim());
              return; // go to next line
            }

            if (!apmConfigFound.get() && apmConfigPattern.matcher(line).matches()) {
              apmConfigFound.set(true);
              return; // go to next line
            }

            if (apmConfigFound.get()) {
              Matcher keyValueMatcher = keyValPattern.matcher(line);
              if (keyValueMatcher.matches()) {
                String key = keyValueMatcher.group(1).trim();
                String value = keyValueMatcher.group(2).trim();
                // If value has quotes, remove them
                if (value.startsWith("\"") && value.endsWith("\"")) {
                  value = value.substring(1, value.length() - 1);
                }
                cfg.put(key, value); // Store key-value pair in map
              } else {
                // If we encounter a non-indented or non-key-value line, stop processing
                apmConfigFound.set(false);
              }
            }
          });
      return cfg;
    }
  }
}
