package datadog.trace.instrumentation.couchbase_32.client;

import com.couchbase.client.core.Core;
import com.couchbase.client.core.cnc.RequestSpan;
import com.couchbase.client.core.msg.RequestContext;
import datadog.trace.api.DDTags;
import datadog.trace.bootstrap.ContextStore;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.InstrumentationTags;
import datadog.trace.bootstrap.instrumentation.api8.java.concurrent.StatusSettable;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class DatadogRequestSpan implements RequestSpan, StatusSettable<Integer> {
  private final AgentSpan span;
  private final ContextStore<Core, String> coreContext;

  // When a QueryRequest is converted into a prepare or execute request, then we need to close
  // the parent span as well, since Couchbase drops it on the floor
  private DatadogRequestSpan convertedParent;
  private DatadogRequestSpan convertedChild;

  // The interaction between error setting in StatusSettingCompletableFuture and the span is a bit
  // involved since we need to make sure that we don't finish the span before we have set the error
  // on it. That's why we have a counter here and let the status setting code finish the span if
  // the code tried to finish the span while the completable future was completed.
  private AtomicInteger endCounter = new AtomicInteger(0);

  // The code in DefaultErrorUtil is sometimes run from inside the completion of a completable
  // future, so we need to ensure that the completion of the future does not overwrite the error
  private AtomicBoolean statusSet = new AtomicBoolean(false);

  private DatadogRequestSpan(AgentSpan span, final ContextStore<Core, String> coreContext) {
    this.span = span;
    this.coreContext = coreContext;
  }

  public static DatadogRequestSpan wrap(
      AgentSpan span, final ContextStore<Core, String> coreContext) {
    return new DatadogRequestSpan(span, coreContext);
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
    if (endCounter.get() == 0) {
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
    if (endCounter.getAndIncrement() == 0) {
      endInternal();
    }
  }

  private void endInternal() {
    CouchbaseClientDecorator.DECORATE.beforeFinish(span);
    span.finish();
    if (null != convertedParent) {
      convertedParent.end();
      convertedParent = null;
    }
  }

  @Override
  public void requestContext(RequestContext requestContext) {
    span.setTag(InstrumentationTags.COUCHBASE_SEED_NODES, coreContext.get(requestContext.core()));
  }

  private boolean shouldSetStatus() {
    return statusSet.compareAndSet(false, true);
  }

  @Override
  public Integer statusStart() {
    return endCounter.getAndIncrement();
  }

  @Override
  public void statusFinished(Integer context) {
    int current = endCounter.decrementAndGet();
    if (current > 0 && context == 0) {
      // There was an attempt to end the RequestSpan while we were in status processing, so end it
      this.endInternal();
    }
  }

  @Override
  public void setSuccess(Integer context) {
    // We can get a call to setSuccess from StatusSettingCompletableFuture before we intercept an
    // exception.
    // So do nothing here.
  }

  @Override
  public void setError(Integer context, Throwable throwable) {
    if (context == 0) {
      setErrorDirectly(throwable);
    }
  }

  public void setErrorDirectly(Throwable throwable) {
    if (shouldSetStatus()) {
      this.span.setError(true);
      this.span.addThrowable(throwable);
      if (null != convertedParent) {
        convertedParent.setErrorDirectly(
            span.getTag(DDTags.ERROR_MSG),
            span.getTag(DDTags.ERROR_TYPE),
            span.getTag(DDTags.ERROR_STACK));
      }
    }
  }

  private void setErrorDirectly(Object errorMsg, Object errorType, Object errorStack) {
    if (shouldSetStatus()) {
      span.setError(true);
      span.setTag(DDTags.ERROR_MSG, errorMsg);
      span.setTag(DDTags.ERROR_TYPE, errorType);
      span.setTag(DDTags.ERROR_STACK, errorStack);
      if (null != convertedParent) {
        convertedParent.setErrorDirectly(errorMsg, errorType, errorStack);
      }
    }
  }
}
