package datadog.trace.instrumentation.camel410;

import datadog.context.Context;
import datadog.context.ContextScope;
import org.apache.camel.AsyncCallback;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.apache.camel.support.processor.DelegateAsyncProcessor;

/**
 * Restores exchange context at execution boundaries, including callbacks on pre-existing threads.
 */
public final class CamelTracingProcessor extends DelegateAsyncProcessor {
  public CamelTracingProcessor(Processor target) {
    super(target);
  }

  @Override
  public boolean process(Exchange exchange, AsyncCallback callback) {
    Context context = CamelState.exchangeContext(exchange);
    if (context == null) {
      return processor.process(exchange, callback);
    }
    try (ContextScope ignored = context.attach()) {
      return processor.process(exchange, new Callback(callback, context));
    }
  }

  public static final class Callback implements AsyncCallback {
    private final AsyncCallback delegate;
    private final Context context;

    public Callback(AsyncCallback delegate, Context context) {
      this.delegate = delegate;
      this.context = context;
    }

    @Override
    public void done(boolean doneSync) {
      try (ContextScope ignored = context.attach()) {
        delegate.done(doneSync);
      }
    }
  }
}
