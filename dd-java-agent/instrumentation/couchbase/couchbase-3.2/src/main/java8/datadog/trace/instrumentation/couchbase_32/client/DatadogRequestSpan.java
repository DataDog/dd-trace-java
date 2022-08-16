package datadog.trace.instrumentation.couchbase_32.client;

import com.couchbase.client.core.cnc.RequestSpan;
import com.couchbase.client.core.msg.RequestContext;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.time.Instant;

public class DatadogRequestSpan implements RequestSpan {
  private final AgentSpan span;
  // When a QueryRequest is converted into a prepare or execute request, then we need to close
  // the parent span as well, since Couchbase drops it on the floor
  private DatadogRequestSpan convertedParent;
  private DatadogRequestSpan convertedChild;

  private DatadogRequestSpan(AgentSpan span) {
    this.span = span;
  }

  public static DatadogRequestSpan wrap(AgentSpan span) {
    return new DatadogRequestSpan(span);
  }

  public static AgentSpan unwrap(RequestSpan span) {
    if (span == null) {
      return null;
    }
    if (span instanceof DatadogRequestSpan) {
      return ((DatadogRequestSpan) span).span;
    } else {
      throw new IllegalArgumentException("RequestSpan must be of type DatadogRequestSpan");
    }
  }

  public void setConvertedParent(RequestSpan convertedParent) {
    if (convertedParent instanceof DatadogRequestSpan) {
      // We need to keep track of previously converted child spans since a query
      // that is converted to an execute statement will create a prepare-span
      // which will be a converted child, but it will never get finished
      DatadogRequestSpan parent = (DatadogRequestSpan) convertedParent;
      this.convertedParent = parent;
      DatadogRequestSpan previous = parent.convertedChild;
      parent.convertedChild = this;
      if (null != previous) {
        previous.setConvertedParent(null);
        previous.end();
      }
    } else {
      this.convertedParent = null;
    }
  }

  @Override
  public void attribute(String key, String value) {
    // TODO when `db.statement` is set here it will be intercepted by the TagInterceptor, so any
    //  sort of obfuscation should go in there, preferably as a lazy sort of Utf8String that does
    //  the actual work at the end
    span.setTag(key, value);
  }

  @Override
  public void attribute(String key, boolean value) {
    span.setTag(key, value);
  }

  @Override
  public void attribute(String key, long value) {
    span.setTag(key, value);
  }

  @Override
  public void event(String name, Instant timestamp) {
    // TODO event support would be nice
  }

  @Override
  public void status(StatusCode status) {
    if (status == null) {
      return;
    }
    switch (status) {
      case OK:
        span.setError(false);
        break;
      case ERROR:
        span.setError(true);
        break;
      default:
    }
  }

  @Override
  public void end() {
    CouchbaseClientDecorator.DECORATE.beforeFinish(span);
    span.finish();
    if (null != convertedParent) {
      convertedParent.end();
      convertedParent = null;
    }
  }

  @Override
  public void requestContext(RequestContext requestContext) {
    // TODO should we add tags/metrics based on the request context when the span ends?
  }
}
