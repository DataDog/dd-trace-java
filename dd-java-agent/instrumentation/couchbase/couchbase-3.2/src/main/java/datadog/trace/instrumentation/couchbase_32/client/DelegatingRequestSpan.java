package datadog.trace.instrumentation.couchbase_32.client;

import com.couchbase.client.core.cnc.RequestSpan;
import com.couchbase.client.core.msg.RequestContext;
import java.time.Instant;
import javax.annotation.Nonnull;

/** RequestSpan, which delegates all calls to two other RequestSpans */
public class DelegatingRequestSpan implements RequestSpan {

  private final RequestSpan ddSpan;
  private final RequestSpan cncSpan;

  public DelegatingRequestSpan(@Nonnull RequestSpan ddSpan, @Nonnull RequestSpan cncSpan) {
    this.ddSpan = ddSpan;
    this.cncSpan = cncSpan;
  }

  public RequestSpan getDatadogSpan() {
    return ddSpan;
  }

  public RequestSpan getCncSpan() {
    return cncSpan;
  }

  @Override
  public void attribute(String key, String value) {
    ddSpan.attribute(key, value);
    cncSpan.attribute(key, value);
  }

  @Override
  public void attribute(String key, boolean value) {
    ddSpan.attribute(key, value);
    cncSpan.attribute(key, value);
  }

  @Override
  public void attribute(String key, long value) {
    ddSpan.attribute(key, value);
    cncSpan.attribute(key, value);
  }

  @Override
  public void event(String name, Instant timestamp) {
    ddSpan.event(name, timestamp);
    cncSpan.event(name, timestamp);
  }

  @Override
  public void status(StatusCode status) {
    ddSpan.status(status);
    cncSpan.status(status);
  }

  @Override
  public void end() {
    try {
      ddSpan.end();
    } finally {
      // guarantee cnc spans get ended even if ddSpan.end() throws exception
      cncSpan.end();
    }
  }

  @Override
  public void requestContext(RequestContext requestContext) {
    ddSpan.requestContext(requestContext);
    cncSpan.requestContext(requestContext);
  }
}
