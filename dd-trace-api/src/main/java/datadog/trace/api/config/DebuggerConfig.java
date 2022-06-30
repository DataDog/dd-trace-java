package datadog.trace.api.config;

public final class DebuggerConfig {
  public static final String DEBUGGER_ENABLED = "debugger.enabled";
  public static final String DEBUGGER_SNAPSHOT_URL = "debugger.snapshot.url";
  public static final String DEBUGGER_PROBE_FILE_LOCATION = "debugger.probe.file";
  public static final String DEBUGGER_UPLOAD_TIMEOUT = "debugger.upload.timeout";
  public static final String DEBUGGER_UPLOAD_FLUSH_INTERVAL = "debugger.upload.flush.interval";
  public static final String DEBUGGER_UPLOAD_BATCH_SIZE = "debugger.upload.batch.size";
  public static final String DEBUGGER_METRICS_ENABLED = "debugger.metrics.enabled";
  public static final String DEBUGGER_CLASSFILE_DUMP_ENABLED = "debugger.classfile.dump.enabled";
  public static final String DEBUGGER_POLL_INTERVAL = "debugger.poll.interval";
  public static final String DEBUGGER_DIAGNOSTICS_INTERVAL = "debugger.diagnostics.interval";
  public static final String DEBUGGER_VERIFY_BYTECODE = "debugger.verify.bytecode";
  public static final String DEBUGGER_INSTRUMENT_THE_WORLD = "debugger.instrument.the.world";
  public static final String DEBUGGER_EXCLUDE_FILE = "debugger.exclude.file";

  private DebuggerConfig() {}
}
