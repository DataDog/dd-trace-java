package datadog.trace.core;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.SpanEvent;

import java.util.concurrent.*;

public class DDSpanEvent extends SpanEvent {
  public DDSpanEvent(String name, AgentSpan.Attributes attributes) {
    super(name, attributes);
  }

  public DDSpanEvent(String name, AgentSpan.Attributes attributes, long timestamp, TimeUnit unit) {
    super(name, attributes, timestamp, unit);
  }
}
