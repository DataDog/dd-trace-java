package datadog.trace.instrumentation.akkahttp;

import static org.junit.jupiter.api.Assertions.assertSame;

import datadog.context.Context;
import datadog.context.ContextKey;
import datadog.context.ContextScope;
import org.junit.jupiter.api.Test;

class SwappedContextScopeTest {
  private static final ContextKey<String> KEY = ContextKey.named("akka-http-swap-test");

  @Test
  void restoresPreviousContext() {
    Context previous = Context.root().with(KEY, "previous");
    Context request = Context.root().with(KEY, "request");

    try (ContextScope previousScope = previous.attach()) {
      try (ContextScope requestScope = new DatadogWrapperHelper.SwappedContextScope(request)) {
        assertSame(request, Context.current());
      }
      assertSame(previous, Context.current());
    }
  }

  @Test
  void waitsUntilRequestContextIsCurrent() {
    Context previous = Context.root().with(KEY, "previous");
    Context request = Context.root().with(KEY, "request");
    Context other = Context.root().with(KEY, "other");

    try (ContextScope previousScope = previous.attach()) {
      ContextScope requestScope = new DatadogWrapperHelper.SwappedContextScope(request);
      try (ContextScope otherScope = other.attach()) {
        requestScope.close();
        assertSame(other, Context.current());
      }
      assertSame(request, Context.current());
      requestScope.close();
      assertSame(previous, Context.current());
    }
  }
}
