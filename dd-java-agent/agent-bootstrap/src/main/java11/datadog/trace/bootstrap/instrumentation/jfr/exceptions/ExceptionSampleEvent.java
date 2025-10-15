package datadog.trace.bootstrap.instrumentation.jfr.exceptions;

import datadog.trace.bootstrap.instrumentation.jfr.ContextualEvent;
import jdk.jfr.Category;
import jdk.jfr.Description;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;

@Name("datadog.ExceptionSample")
@Label("ExceptionSample")
@Description("Datadog exception sample event.")
@Category("Datadog")
public class ExceptionSampleEvent extends Event implements ContextualEvent {
  @Label("Exception Type")
  private final String type;

  @Label("Exception message")
  private final String message;

  @Label("Sampled")
  private final boolean sampled;

  @Label("First occurrence")
  private final boolean firstOccurrence;

  @Label("Local Root Span Id")
  private long localRootSpanId;

  @Label("Span Id")
  private long spanId;

  public ExceptionSampleEvent(Throwable e, boolean sampled, boolean firstOccurrence) {
    /*
     * TODO: we should have some tests for this class.
     * Unfortunately at the moment this is not easily possible because we cannot build tests with groovy that
     * are compiled against java11 SDK - this seems to be gradle-groovy interaction limitation.
     * Writing these tests in java seems like would introduce more noise.
     */
    this.type = e.getClass().getName();
    this.message = getMessage(e);
    this.sampled = sampled;
    this.firstOccurrence = firstOccurrence;
    captureContext();
  }

  private static String getMessage(Throwable t) {
    final ExceptionProfiling exceptionProfiling = ExceptionProfiling.getInstance();
    if (exceptionProfiling != null && exceptionProfiling.recordExceptionMessage()) {
      try {
        return t.getMessage();
      } catch (Throwable ignored) {
        // apparently there might be exceptions throwing at least NPE when trying to get the message
      }
    }
    return null;
  }

  @Override
  public void setContext(long localRootSpanId, long spanId) {
    this.localRootSpanId = localRootSpanId;
    this.spanId = spanId;
  }
}
