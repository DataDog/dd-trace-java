package datadog.trace.instrumentation.googlehttpclient;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import edu.umd.cs.findbugs.annotations.NonNull;

public class RequestState {

  private final AgentSpan span;

  public RequestState(AgentSpan span) {
    this.span = span;
  }

  @NonNull
  public AgentSpan getSpan() {
    return span;
  }
}
