package com.datadog.profiling.controller;

import datadog.trace.api.config.ProfilingConfig;
import datadog.trace.bootstrap.config.provider.ConfigProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;

public final class ProfilingSystemConfig {
  private static final Logger log = LoggerFactory.getLogger(ProfilingSystemConfig.class);

  public enum Subsystem {
    CPU("cpu"), WALLCLOCK("wall"), ALLOCATIONS("allocs"), HEAP("heap"), EXCEPTIONS("exceptions");

    private final String id;
    Subsystem(String id) {
      this.id = id;
    }

    public String getId() {
      return id;
    }
  }

  private final Set<Subsystem> enabled;
  private final Duration snapshotInterval;
  private final Duration startupDelay;
  private final boolean profileStartup;

  ProfilingSystemConfig() {
    this.enabled = Collections.emptySet();
    this.snapshotInterval = Duration.ofSeconds(1);
    this.profileStartup = false;
    this.startupDelay = Duration.ZERO;
  }

  public ProfilingSystemConfig(ConfigProvider cfgProvider) throws ConfigurationException {
    String subsystems = cfgProvider.getString(ProfilingConfig.PROFILING_ENABLED, ProfilingConfig.PROFILING_ENABLED_DEFAULT).toLowerCase();

    Set<Subsystem> enabled;
    if (Boolean.parseBoolean(subsystems)) {
      enabled = EnumSet.allOf(Subsystem.class);
    } else {
      if (subsystems.equals("false") || subsystems.equals("none")) {
        enabled = EnumSet.noneOf(Subsystem.class);
      } else {
        String[] values = subsystems.split(",");
        enabled = new HashSet<>(values.length);
        for (String value : values) {
          switch (value) {
            case "cpu": enabled.add(Subsystem.CPU); break;
            case "wall": enabled.add(Subsystem.WALLCLOCK); break;
            case "allocs": enabled.add(Subsystem.ALLOCATIONS); break;
            case "heap": enabled.add(Subsystem.HEAP); break;
            case "exceptions": enabled.add(Subsystem.EXCEPTIONS); break;
            default: {
              log.warn("Unrecognized profiling subsystem: {}", value);
            }
          }
        }
      }
    }
    this.enabled = Collections.unmodifiableSet(enabled);
    this.snapshotInterval = Duration.ofSeconds(cfgProvider.getInteger(ProfilingConfig.PROFILING_UPLOAD_PERIOD, ProfilingConfig.PROFILING_UPLOAD_PERIOD_DEFAULT));
    this.profileStartup = cfgProvider.getBoolean(ProfilingConfig.PROFILING_START_FORCE_FIRST, ProfilingConfig.PROFILING_START_FORCE_FIRST_DEFAULT);
    this.startupDelay = Duration.ofSeconds(cfgProvider.getInteger(ProfilingConfig.PROFILING_START_DELAY, ProfilingConfig.PROFILING_START_DELAY));

    validate();
  }

  ProfilingSystemConfig(Set<Subsystem> subsystems, Duration snapshotInterval, boolean profileStartup, Duration startupDelay) throws ConfigurationException{
    this.enabled = Collections.unmodifiableSet(subsystems);
    this.snapshotInterval = snapshotInterval;
    this.profileStartup = profileStartup;
    this.startupDelay = startupDelay;
    validate();
  }

  private void validate() throws ConfigurationException{
    if (snapshotInterval.isNegative() || snapshotInterval.isZero()) {
      throw new ConfigurationException("Snapshot interval must be positive.");
    }
    if (startupDelay.isNegative()) {
      throw new ConfigurationException("Startup delay must be non-negative.");
    }
  }

  public boolean isEnabled(Subsystem subsystem) {
    return enabled.contains(subsystem);
  }

  public Duration getSnapshotInterval() {
    return snapshotInterval;
  }

  public boolean isProfilingStartup() {
    return profileStartup;
  }

  public Duration getStartupDelay() {
    return startupDelay;
  }

  public ProfilingSystemConfig withEnabled(EnumSet<Subsystem> enabled) throws ConfigurationException {
    return new ProfilingSystemConfig(enabled, this.snapshotInterval, this.profileStartup, this.startupDelay);
  }

  public ProfilingSystemConfig withSnapshotInterval(Duration snapshotInterval) throws ConfigurationException {
    return new ProfilingSystemConfig(this.enabled, snapshotInterval, this.profileStartup, this.startupDelay);
  }

  public ProfilingSystemConfig withStartupDelay(Duration startupDelay) throws ConfigurationException {
    return new ProfilingSystemConfig(this.enabled, this.snapshotInterval, this.profileStartup, startupDelay);
  }

  public ProfilingSystemConfig withProfileStartup(boolean profileStartup) throws ConfigurationException {
    return new ProfilingSystemConfig(this.enabled, this.snapshotInterval, profileStartup, this.startupDelay);
  }
}
