package datadog.trace.bootstrap.instrumentation.jfr.exceptions;

import jdk.jfr.Category;
import jdk.jfr.Description;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;

@Name("datadog.ExceptionSample")
@Label("ExceptionSample")
@Description("Datadog exception sample event.")
@Category("Datadog")
public class ExceptionSampleEvent extends Event {
  @Label("Exception Type")
  private final String type;

  @Label("Exception message")
  private final String message;

  /** JFR may truncate the stack trace - so store original length as well. */
  @Label("Exception stackdepth")
  private final int stackDepth;

  @Label("Sampled")
  private final boolean sampled;

  @Label("First occurrence")
  private final boolean firstOccurrence;

  public ExceptionSampleEvent(
      final Throwable e, final boolean sampled, final boolean firstOccurrence) {
    /*
     * TODO: we should have some tests for this class.
     * Unfortunately at the moment this is not easily possible because we cannot build tests with groovy that
     * are compiled against java11 SDK - this seems to be gradle-groovy interaction limitation.
     * Writing these tests in java seems like would introduce more noise.
     */
    type = e.getClass().getName();
    message = getMessage(e);
    stackDepth = getStackDepth(e);
    this.sampled = sampled;
    this.firstOccurrence = firstOccurrence;
  }

  private static String getMessage(Throwable t) {
    try {
      return t.getMessage();
    } catch (Throwable ignored) {
      // apparently there might be exceptions throwing at least NPE when trying to get the message
    }
    return null;
  }

  private static int getStackDepth(Throwable t) {
    try {
      return t.getStackTrace().length;
    } catch (Throwable ignored) {
      // be defensive about exceptions choking on a call to getStackTrace()
    }
    return 0;
  }
}
