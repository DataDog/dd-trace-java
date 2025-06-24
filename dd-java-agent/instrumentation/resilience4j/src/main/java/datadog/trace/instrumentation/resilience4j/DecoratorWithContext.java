package datadog.trace.instrumentation.resilience4j;

import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;

public abstract class DecoratorWithContext {
  // Use an array so that it can be created and referenced before the span. This is necessary
  // because the holder is created when the innermost decorator is initialized, and then it is
  // referenced in the outer decorators until the outermost one, where the span is created and
  // finished.
  private final AgentSpan[] spanHolder;
  private boolean isOwner = true;

  protected DecoratorWithContext(Object contextHolder) {
    if (contextHolder instanceof DecoratorWithContext) {
      DecoratorWithContext that = (DecoratorWithContext) contextHolder;
      this.spanHolder = that.takeOwnership();
    } else {
      this.spanHolder = new AgentSpan[] {null};
    }
  }

  private AgentSpan[] takeOwnership() {
    isOwner = false;
    return spanHolder;
  }

  protected AgentScope activateDecoratorScope() {
    if (spanHolder[0] == null) {
      // TODO move this to the decorator
      spanHolder[0] = AgentTracer.startSpan(DDContext.INSTRUMENTATION_NAME, DDContext.SPAN_NAME);
    }
    //    return AgentTracer.activateSpan(spanHolder[0]);
    AgentScope scope = AgentTracer.activateSpan(spanHolder[0]);
    return new AgentScope() {
      @Override
      public AgentSpan span() {
        return scope.span();
      }

      @Override
      public void close() {
        scope.close();
        finishSpanIfNeeded();
      }
    };
  }

  // TODO consider making this part of the closable
  private void finishSpanIfNeeded() {
    if (isOwner) {
      // TODO move this to the decorator
      spanHolder[0].finish();
      spanHolder[0] = null;
    }
  }
}
