package datadog.trace.api.config;

import static datadog.trace.api.config.DebuggerConfig.DEBUGGER_CLASSFILE_DUMP_ENABLED;
import static datadog.trace.api.config.DebuggerConfig.DEBUGGER_DIAGNOSTICS_INTERVAL;
import static datadog.trace.api.config.DebuggerConfig.DEBUGGER_ENABLED;
import static datadog.trace.api.config.DebuggerConfig.DEBUGGER_EXCLUDE_FILE;
import static datadog.trace.api.config.DebuggerConfig.DEBUGGER_INSTRUMENT_THE_WORLD;
import static datadog.trace.api.config.DebuggerConfig.DEBUGGER_MAX_PAYLOAD_SIZE;
import static datadog.trace.api.config.DebuggerConfig.DEBUGGER_METRICS_ENABLED;
import static datadog.trace.api.config.DebuggerConfig.DEBUGGER_POLL_INTERVAL;
import static datadog.trace.api.config.DebuggerConfig.DEBUGGER_PROBE_FILE_LOCATION;
import static datadog.trace.api.config.DebuggerConfig.DEBUGGER_UPLOAD_BATCH_SIZE;
import static datadog.trace.api.config.DebuggerConfig.DEBUGGER_UPLOAD_FLUSH_INTERVAL;
import static datadog.trace.api.config.DebuggerConfig.DEBUGGER_UPLOAD_TIMEOUT;
import static datadog.trace.api.config.DebuggerConfig.DEBUGGER_VERIFY_BYTECODE;
import static datadog.trace.api.config.DebuggerConfig.DEFAULT_DEBUGGER_CLASSFILE_DUMP_ENABLED;
import static datadog.trace.api.config.DebuggerConfig.DEFAULT_DEBUGGER_DIAGNOSTICS_INTERVAL;
import static datadog.trace.api.config.DebuggerConfig.DEFAULT_DEBUGGER_ENABLED;
import static datadog.trace.api.config.DebuggerConfig.DEFAULT_DEBUGGER_INSTRUMENT_THE_WORLD;
import static datadog.trace.api.config.DebuggerConfig.DEFAULT_DEBUGGER_MAX_PAYLOAD_SIZE;
import static datadog.trace.api.config.DebuggerConfig.DEFAULT_DEBUGGER_METRICS_ENABLED;
import static datadog.trace.api.config.DebuggerConfig.DEFAULT_DEBUGGER_POLL_INTERVAL;
import static datadog.trace.api.config.DebuggerConfig.DEFAULT_DEBUGGER_UPLOAD_BATCH_SIZE;
import static datadog.trace.api.config.DebuggerConfig.DEFAULT_DEBUGGER_UPLOAD_FLUSH_INTERVAL;
import static datadog.trace.api.config.DebuggerConfig.DEFAULT_DEBUGGER_UPLOAD_TIMEOUT;
import static datadog.trace.api.config.DebuggerConfig.DEFAULT_DEBUGGER_VERIFY_BYTECODE;
import static datadog.trace.api.config.GeneralConfig.RUNTIME_METRICS_ENABLED;

import datadog.trace.bootstrap.config.provider.ConfigProvider;

public class DebuggerFeatureConfig extends AbstractFeatureConfig {
  private final TracerFeatureConfig tracerConfig;
  private final boolean debuggerEnabled;
  private final int debuggerUploadTimeout;
  private final int debuggerUploadFlushInterval;
  private final boolean debuggerClassFileDumpEnabled;
  private final int debuggerPollInterval;
  private final int debuggerDiagnosticsInterval;
  private final boolean debuggerMetricEnabled;
  private final String debuggerProbeFileLocation;
  private final int debuggerUploadBatchSize;
  private final long debuggerMaxPayloadSize;
  private final boolean debuggerVerifyByteCode;
  private final boolean debuggerInstrumentTheWorld;
  private final String debuggerExcludeFile;

  public DebuggerFeatureConfig(ConfigProvider configProvider, TracerFeatureConfig tracerConfig) {
    super(configProvider);
    this.tracerConfig = tracerConfig;
    this.debuggerEnabled = configProvider.getBoolean(DEBUGGER_ENABLED, DEFAULT_DEBUGGER_ENABLED);
    this.debuggerUploadTimeout =
        configProvider.getInteger(DEBUGGER_UPLOAD_TIMEOUT, DEFAULT_DEBUGGER_UPLOAD_TIMEOUT);
    this.debuggerUploadFlushInterval =
        configProvider.getInteger(
            DEBUGGER_UPLOAD_FLUSH_INTERVAL, DEFAULT_DEBUGGER_UPLOAD_FLUSH_INTERVAL);
    this.debuggerClassFileDumpEnabled =
        configProvider.getBoolean(
            DEBUGGER_CLASSFILE_DUMP_ENABLED, DEFAULT_DEBUGGER_CLASSFILE_DUMP_ENABLED);
    this.debuggerPollInterval =
        configProvider.getInteger(DEBUGGER_POLL_INTERVAL, DEFAULT_DEBUGGER_POLL_INTERVAL);
    this.debuggerDiagnosticsInterval =
        configProvider.getInteger(
            DEBUGGER_DIAGNOSTICS_INTERVAL, DEFAULT_DEBUGGER_DIAGNOSTICS_INTERVAL);
    boolean runtimeMetricsEnabled = configProvider.getBoolean(RUNTIME_METRICS_ENABLED, true);
    this.debuggerMetricEnabled =
        runtimeMetricsEnabled
            && configProvider.getBoolean(
                DEBUGGER_METRICS_ENABLED, DEFAULT_DEBUGGER_METRICS_ENABLED);
    this.debuggerProbeFileLocation = configProvider.getString(DEBUGGER_PROBE_FILE_LOCATION);
    this.debuggerUploadBatchSize =
        configProvider.getInteger(DEBUGGER_UPLOAD_BATCH_SIZE, DEFAULT_DEBUGGER_UPLOAD_BATCH_SIZE);
    this.debuggerMaxPayloadSize =
        configProvider.getInteger(DEBUGGER_MAX_PAYLOAD_SIZE, DEFAULT_DEBUGGER_MAX_PAYLOAD_SIZE)
            * 1024L;
    this.debuggerVerifyByteCode =
        configProvider.getBoolean(DEBUGGER_VERIFY_BYTECODE, DEFAULT_DEBUGGER_VERIFY_BYTECODE);
    this.debuggerInstrumentTheWorld =
        configProvider.getBoolean(
            DEBUGGER_INSTRUMENT_THE_WORLD, DEFAULT_DEBUGGER_INSTRUMENT_THE_WORLD);
    this.debuggerExcludeFile = configProvider.getString(DEBUGGER_EXCLUDE_FILE);
  }

  public boolean isDebuggerEnabled() {
    return this.debuggerEnabled;
  }

  public int getDebuggerUploadTimeout() {
    return this.debuggerUploadTimeout;
  }

  public int getDebuggerUploadFlushInterval() {
    return this.debuggerUploadFlushInterval;
  }

  public boolean isDebuggerClassFileDumpEnabled() {
    return this.debuggerClassFileDumpEnabled;
  }

  public int getDebuggerPollInterval() {
    return this.debuggerPollInterval;
  }

  public int getDebuggerDiagnosticsInterval() {
    return this.debuggerDiagnosticsInterval;
  }

  public boolean isDebuggerMetricsEnabled() {
    return this.debuggerMetricEnabled;
  }

  public int getDebuggerUploadBatchSize() {
    return this.debuggerUploadBatchSize;
  }

  public long getDebuggerMaxPayloadSize() {
    return this.debuggerMaxPayloadSize;
  }

  public boolean isDebuggerVerifyByteCode() {
    return this.debuggerVerifyByteCode;
  }

  public boolean isDebuggerInstrumentTheWorld() {
    return this.debuggerInstrumentTheWorld;
  }

  public String getDebuggerExcludeFile() {
    return this.debuggerExcludeFile;
  }

  public String getDebuggerProbeFileLocation() {
    return this.debuggerProbeFileLocation;
  }

  public String getFinalDebuggerProbeUrl() {
    // by default poll from datadog agent
    return "http://" + this.tracerConfig.getAgentHost() + ":" + this.tracerConfig.getAgentPort();
  }

  public String getFinalDebuggerSnapshotUrl() {
    // by default send to datadog agent
    return this.tracerConfig.getAgentUrl() + "/debugger/v1/input";
  }

  @Override
  public String toString() {
    return "DebuggerFeatureConfig{"
        + "tracerConfig="
        + this.tracerConfig
        + ", debuggerEnabled="
        + this.debuggerEnabled
        + ", debuggerUploadTimeout="
        + this.debuggerUploadTimeout
        + ", debuggerUploadFlushInterval="
        + this.debuggerUploadFlushInterval
        + ", debuggerClassFileDumpEnabled="
        + this.debuggerClassFileDumpEnabled
        + ", debuggerPollInterval="
        + this.debuggerPollInterval
        + ", debuggerDiagnosticsInterval="
        + this.debuggerDiagnosticsInterval
        + ", debuggerMetricEnabled="
        + this.debuggerMetricEnabled
        + ", debuggerProbeFileLocation='"
        + this.debuggerProbeFileLocation
        + '\''
        + ", debuggerUploadBatchSize="
        + this.debuggerUploadBatchSize
        + ", debuggerMaxPayloadSize="
        + this.debuggerMaxPayloadSize
        + ", debuggerVerifyByteCode="
        + this.debuggerVerifyByteCode
        + ", debuggerInstrumentTheWorld="
        + this.debuggerInstrumentTheWorld
        + ", debuggerExcludeFile='"
        + this.debuggerExcludeFile
        + '\''
        + '}';
  }
}
