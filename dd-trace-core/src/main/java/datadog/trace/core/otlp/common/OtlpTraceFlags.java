package datadog.trace.core.otlp.common;

/** OTLP's {@code Span.flags} bit values, shared by the trace and logs proto/JSON encoders. */
public final class OtlpTraceFlags {
  private OtlpTraceFlags() {}

  public static final int NO_TRACE_FLAGS = 0x00000000;
  public static final int SAMPLED_TRACE_FLAG = 0x00000001;
  public static final int REMOTE_TRACE_FLAG = 0x00000300;
}
