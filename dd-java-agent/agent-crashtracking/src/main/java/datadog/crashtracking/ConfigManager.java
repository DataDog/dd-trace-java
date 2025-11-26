package datadog.crashtracking;

import static datadog.crashtracking.Initializer.PID_PREFIX;
import static datadog.trace.api.telemetry.LogCollector.SEND_TELEMETRY;
import static datadog.trace.util.AgentThreadFactory.AGENT_THREAD_GROUP;

import datadog.environment.SystemProperties;
import datadog.trace.api.Config;
import datadog.trace.api.ProcessTags;
import datadog.trace.api.WellKnownTags;
import datadog.trace.util.PidHelper;
import datadog.trace.util.RandomUtils;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ConfigManager {
  private static final Logger LOGGER = LoggerFactory.getLogger(ConfigManager.class);
  private static final Pattern EQUALS_SPLITTER = Pattern.compile("=");

  public static class StoredConfig {
    final String service;
    final String env;
    final String version;
    final String tags;
    final String processTags;
    final String runtimeId;
    final String reportUUID;
    final boolean agentless;
    final boolean sendToErrorTracking;

    StoredConfig(
        String reportUUID,
        String service,
        String env,
        String version,
        String tags,
        String processTags,
        String runtimeId,
        boolean agentless,
        boolean sendToErrorTracking) {
      this.service = service;
      this.env = env;
      this.version = version;
      this.tags = tags;
      this.processTags = processTags;
      this.runtimeId = runtimeId;
      this.reportUUID = reportUUID;
      this.agentless = agentless;
      this.sendToErrorTracking = sendToErrorTracking;
    }

    public static class Builder {
      String service;
      String env;
      String version;
      String tags;
      String processTags;
      String runtimeId;
      String reportUUID;
      boolean agentless;
      boolean sendToErrorTracking;

      public Builder(Config config) {
        // get sane defaults
        this.service = config.getServiceName();
        this.env = config.getEnv();
        this.version = config.getVersion();
        this.runtimeId = config.getRuntimeId();
        this.reportUUID = RandomUtils.randomUUID().toString();
        this.agentless = config.isCrashTrackingAgentless();
        this.sendToErrorTracking = config.isCrashTrackingErrorsIntakeEnabled();
      }

      public Builder service(String service) {
        this.service = service;
        return this;
      }

      public Builder env(String env) {
        this.env = env;
        return this;
      }

      public Builder version(String version) {
        this.version = version;
        return this;
      }

      public Builder tags(String tags) {
        this.tags = tags;
        return this;
      }

      public Builder processTags(String processTags) {
        this.processTags = processTags;
        return this;
      }

      public Builder runtimeId(String runtimeId) {
        this.runtimeId = runtimeId;
        return this;
      }

      public Builder sendToErrorTracking(boolean sendToErrorTracking) {
        this.sendToErrorTracking = sendToErrorTracking;
        return this;
      }

      public Builder agentless(boolean agentless) {
        this.agentless = agentless;
        return this;
      }

      // @VisibleForTesting
      Builder reportUUID(String reportUUID) {
        this.reportUUID = reportUUID;
        return this;
      }

      public StoredConfig build() {
        return new StoredConfig(
            reportUUID,
            service,
            env,
            version,
            tags,
            processTags,
            runtimeId,
            agentless,
            sendToErrorTracking);
      }
    }
  }

  private ConfigManager() {}

  private static String getBaseName(Path path) {
    String filename = path.getFileName().toString();
    int dotIndex = filename.lastIndexOf('.');
    if (dotIndex == -1) {
      return filename;
    }
    return filename.substring(0, dotIndex);
  }

  // @VisibleForTesting
  static String getMergedTagsForSerialization(Config config) {
    return config.getMergedCrashTrackingTags().entrySet().stream()
        .filter(e -> e.getValue() != null)
        .map(e -> e.getKey() + ":" + e.getValue())
        .collect(Collectors.joining(","));
  }

  private static void writeEntry(BufferedWriter writer, CharSequence key, CharSequence value)
      throws IOException {
    if (key == null || value == null) {
      return;
    }
    writer.write(key.toString());
    writer.write('=');
    writer.write(value.toString());
    writer.newLine();
  }

  public static void writeConfigToPath(Path scriptPath, String... additionalEntries) {
    String cfgFileName = getBaseName(scriptPath) + PID_PREFIX + PidHelper.getPid() + ".cfg";
    Path cfgPath = scriptPath.resolveSibling(cfgFileName);
    writeConfigToFile(Config.get(), cfgPath, additionalEntries);
  }

  // @VisibleForTesting
  static void writeConfigToFile(Config config, Path cfgPath, String... additionalEntries) {
    final WellKnownTags wellKnownTags = config.getWellKnownTags();

    LOGGER.debug("Writing config file: {}", cfgPath);
    try (BufferedWriter bw = Files.newBufferedWriter(cfgPath)) {
      for (int i = 0; i < additionalEntries.length; i += 2) {
        writeEntry(bw, additionalEntries[i], additionalEntries[i + 1]);
      }
      writeEntry(bw, "tags", getMergedTagsForSerialization(config));
      writeEntry(bw, "service", wellKnownTags.getService());
      writeEntry(bw, "version", wellKnownTags.getVersion());
      writeEntry(bw, "env", wellKnownTags.getEnv());
      writeEntry(bw, "process_tags", ProcessTags.getTagsForSerialization());
      writeEntry(bw, "runtime_id", wellKnownTags.getRuntimeId());
      writeEntry(bw, "java_home", SystemProperties.get("java.home"));
      writeEntry(bw, "agentless", Boolean.toString(config.isCrashTrackingAgentless()));
      writeEntry(bw, "upload_to_et", Boolean.toString(config.isCrashTrackingErrorsIntakeEnabled()));

      Runtime.getRuntime()
          .addShutdownHook(
              new Thread(
                  AGENT_THREAD_GROUP,
                  () -> {
                    try {
                      LOGGER.debug("Deleting config file: {}", cfgPath);
                      Files.deleteIfExists(cfgPath);
                    } catch (IOException e) {
                      LOGGER.warn(SEND_TELEMETRY, "Failed deleting config file: {}", cfgPath, e);
                    }
                  }));
      LOGGER.debug("Config file written: {}", cfgPath);
    } catch (IOException e) {
      LOGGER.warn(SEND_TELEMETRY, "Failed writing config file: {}", cfgPath);
      try {
        Files.deleteIfExists(cfgPath);
      } catch (IOException ignored) {
        // ignore
      }
    }
  }

  @Nullable
  public static StoredConfig readConfig(Config config, Path scriptPath) {
    try (final BufferedReader reader = Files.newBufferedReader(scriptPath)) {
      final StoredConfig.Builder cfgBuilder = new StoredConfig.Builder(config);
      String line;
      while ((line = reader.readLine()) != null) {
        if (line.isEmpty()) {
          continue;
        }
        String[] parts = EQUALS_SPLITTER.split(line, 2);
        if (parts.length != 2) {
          continue;
        }
        final String value = parts[1];
        switch (parts[0]) {
          case "tags":
            cfgBuilder.tags(value);
            break;
          case "service":
            cfgBuilder.service(value);
            break;
          case "env":
            cfgBuilder.env(value);
            break;
          case "version":
            cfgBuilder.version(value);
            break;
          case "process_tags":
            cfgBuilder.processTags(value);
            break;
          case "runtime_id":
            cfgBuilder.runtimeId(value);
            break;
          case "agentless":
            cfgBuilder.agentless(Boolean.parseBoolean(value));
            break;
          case "upload_to_et":
            cfgBuilder.sendToErrorTracking(Boolean.parseBoolean(value));
            break;
          default:
            // ignore
            break;
        }
      }
      return cfgBuilder.build();
    } catch (Throwable t) {
      LOGGER.error("Failed to read config file: {}", scriptPath, t);
    }
    return null;
  }
}
