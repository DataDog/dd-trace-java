package datadog.trace.instrumentation.camel;

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
    Context context = CamelState.context(exchange);
    ContextScope scope = attach(context);
    try {
      return processor.process(exchange, new Callback(callback, context));
    } finally {
      close(scope);
    }
  }

  private static ContextScope attach(Context context) {
    try {
      return context.attach();
    } catch (Throwable ignored) {
      return null;
    }
  }

  private static void close(ContextScope scope) {
    try {
      if (scope != null) {
        scope.close();
      }
    } catch (Throwable ignored) {
      // Never change a processor's result or callback when context cleanup fails.
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
      ContextScope scope = attach(context);
      try {
        delegate.done(doneSync);
      } finally {
        close(scope);
      }
    }
  }
}
