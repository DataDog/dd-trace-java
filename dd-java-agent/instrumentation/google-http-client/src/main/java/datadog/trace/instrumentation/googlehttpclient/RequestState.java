package datadog.trace.instrumentation.googlehttpclient;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import javax.annotation.Nonnull;

public class RequestState {

  private final AgentSpan span;

  public RequestState(AgentSpan span) {
    this.span = span;
  }

  @Nonnull
  public AgentSpan getSpan() {
    return span;
  }
}
