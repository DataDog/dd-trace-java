package com.datadog.debugger.agent;

import com.datadog.debugger.probe.ProbeDefinition;
import datadog.trace.api.Config;
import datadog.trace.util.TagsHelper;
import java.util.Arrays;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Checks of definition tags matches the configured environment and version */
public class EnvironmentAndVersionChecker {
  private static final Logger log = LoggerFactory.getLogger(EnvironmentAndVersionChecker.class);
  private static final ProbeDefinition.Tag VERSION_TAG = new ProbeDefinition.Tag("version");
  private static final ProbeDefinition.Tag ENV_TAG = new ProbeDefinition.Tag("env");
  private final String environment;
  private final String version;

  public EnvironmentAndVersionChecker(Config config) {
    environment = TagsHelper.sanitize(config.getEnv());
    version = TagsHelper.sanitize(config.getVersion());
  }

  public boolean isEnvAndVersionMatch(ProbeDefinition definition) {
    if (definition.getTags() == null || definition.getTags().length == 0) {
      return true;
    }

    ProbeDefinition.Tag probeEnv =
        Arrays.stream(definition.getTags())
            .filter(t -> t.getKey().equals("env"))
            .findFirst()
            .orElse(ENV_TAG);
    ProbeDefinition.Tag probeVersion =
        Arrays.stream(definition.getTags())
            .filter(t -> t.getKey().equals("version"))
            .findFirst()
            .orElse(VERSION_TAG);

    boolean envMatch = !probeEnv.hasValue() || probeEnv.getValue().equals(environment);
    boolean versionMatch = !probeVersion.hasValue() || probeVersion.getValue().equals(version);
    boolean probeMatches = envMatch && versionMatch;

    if (!probeMatches) {
      log.debug(
          "Filtering out probe based on agent environment ({}) or version ({}): {}",
          environment,
          version,
          definition);
    }
    return probeMatches;
  }
}
