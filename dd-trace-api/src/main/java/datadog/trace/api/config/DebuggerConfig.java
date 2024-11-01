package datadog.trace.api.config;

public final class DebuggerConfig {
  public static final String DEBUGGER_ENABLED = "dynamic.instrumentation.enabled";
  public static final String DEBUGGER_SNAPSHOT_URL = "dynamic.instrumentation.snapshot.url";
  public static final String DEBUGGER_PROBE_URL = "dynamic.instrumentation.probe.url";
  public static final String DEBUGGER_PROBE_FILE_LOCATION = "dynamic.instrumentation.probe.file";
  public static final String DEBUGGER_UPLOAD_TIMEOUT = "dynamic.instrumentation.upload.timeout";
  public static final String DEBUGGER_UPLOAD_FLUSH_INTERVAL =
      "dynamic.instrumentation.upload.flush.interval";
  public static final String DEBUGGER_UPLOAD_BATCH_SIZE =
      "dynamic.instrumentation.upload.batch.size";
  public static final String DEBUGGER_MAX_PAYLOAD_SIZE = "dynamic.instrumentation.max.payload.size";
  public static final String DEBUGGER_METRICS_ENABLED = "dynamic.instrumentation.metrics.enabled";
  public static final String DEBUGGER_CLASSFILE_DUMP_ENABLED =
      "dynamic.instrumentation.classfile.dump.enabled";
  public static final String DEBUGGER_POLL_INTERVAL = "dynamic.instrumentation.poll.interval";
  public static final String DEBUGGER_DIAGNOSTICS_INTERVAL =
      "dynamic.instrumentation.diagnostics.interval";
  public static final String DEBUGGER_VERIFY_BYTECODE = "dynamic.instrumentation.verify.bytecode";
  public static final String DEBUGGER_INSTRUMENT_THE_WORLD =
      "dynamic.instrumentation.instrument.the.world";
  public static final String DEBUGGER_EXCLUDE_FILES = "dynamic.instrumentation.exclude.files";
  public static final String DEBUGGER_INCLUDE_FILES = "dynamic.instrumentation.include.files";
  public static final String DEBUGGER_CAPTURE_TIMEOUT = "dynamic.instrumentation.capture.timeout";
  public static final String DEBUGGER_REDACTED_IDENTIFIERS =
      "dynamic.instrumentation.redacted.identifiers";
  public static final String DEBUGGER_REDACTION_EXCLUDED_IDENTIFIERS =
      "dynamic.instrumentation.redaction.excluded.identifiers";
  public static final String DEBUGGER_REDACTED_TYPES = "dynamic.instrumentation.redacted.types";
  public static final String DEBUGGER_SYMBOL_ENABLED = "symbol.database.upload.enabled";
  public static final String DEBUGGER_SYMBOL_FORCE_UPLOAD = "internal.force.symbol.database.upload";
  public static final String DEBUGGER_SYMBOL_INCLUDES = "symbol.database.includes";
  public static final String DEBUGGER_SYMBOL_FLUSH_THRESHOLD = "symbol.database.flush.threshold";
  public static final String DEBUGGER_SYMBOL_COMPRESSED = "symbol.database.compressed";
  public static final String DEBUGGER_EXCEPTION_ENABLED = "exception.debugging.enabled";
  public static final String EXCEPTION_REPLAY_ENABLED = "exception.replay.enabled";
  public static final String DEBUGGER_MAX_EXCEPTION_PER_SECOND =
      "exception.replay.max.exception.analysis.limit";
  public static final String DEBUGGER_EXCEPTION_ONLY_LOCAL_ROOT =
      "internal.exception.replay.only.local.root";
  public static final String DEBUGGER_EXCEPTION_MAX_CAPTURED_FRAMES =
      "exception.replay.max.frames.to.capture";
  public static final String DEBUGGER_EXCEPTION_CAPTURE_MAX_FRAMES =
      "exception.replay.capture.max.frames";
  public static final String DEBUGGER_EXCEPTION_CAPTURE_INTERVAL_SECONDS =
      "exception.replay.capture.interval.seconds";
  public static final String DEBUGGER_EXCEPTION_CAPTURE_INTERMEDIATE_SPANS_ENABLED =
      "exception.replay.capture.intermediate.spans.enabled";
  public static final String THIRD_PARTY_INCLUDES = "third.party.includes";
  public static final String THIRD_PARTY_EXCLUDES = "third.party.excludes";

  private DebuggerConfig() {}
}
