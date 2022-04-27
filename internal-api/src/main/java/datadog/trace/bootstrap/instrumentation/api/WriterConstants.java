package datadog.trace.bootstrap.instrumentation.api;

public final class WriterConstants {
  public static final String DD_AGENT_WRITER_TYPE = "DDAgentWriter";
  public static final String DD_INTAKE_WRITER_TYPE = "DDIntakeWriter";
  public static final String LOGGING_WRITER_TYPE = "LoggingWriter";
  public static final String PRINTING_WRITER_TYPE = "PrintingWriter";
  public static final String TRACE_STRUCTURE_WRITER_TYPE = "TraceStructureWriter";
  public static final String MULTI_WRITER_TYPE = "MultiWriter";

  private WriterConstants() {}
}
