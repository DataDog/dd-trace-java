package datadog.trace.util.stacktrace;

import java.util.List;
import javax.annotation.Nullable;

/**
 * Represents a batch of stack traces to be sent to the agent via meta struct. Feel free to add more
 * List<StackTraceEvent> fields if needed for other products.
 */
public class StackTraceBatch {

  public static final String META_STRUCT_KEY = "_dd.stack";

  // Stack traces for exploit events (RASP)
  @Nullable private List<StackTraceEvent> exploit;

  // Stack traces for vulnerability events (IAST)
  @Nullable private List<StackTraceEvent> vulnerability;

  public StackTraceBatch() {}

  public StackTraceBatch(
      @Nullable final List<StackTraceEvent> exploit,
      @Nullable final List<StackTraceEvent> vulnerability) {
    this.exploit = exploit;
    this.vulnerability = vulnerability;
  }

  @Nullable
  public List<StackTraceEvent> getExploit() {
    return exploit;
  }

  @Nullable
  public List<StackTraceEvent> getVulnerability() {
    return vulnerability;
  }

  public void setExploit(@Nullable final List<StackTraceEvent> exploit) {
    this.exploit = exploit;
  }

  public void setVulnerability(@Nullable final List<StackTraceEvent> vulnerability) {
    this.vulnerability = vulnerability;
  }
}
