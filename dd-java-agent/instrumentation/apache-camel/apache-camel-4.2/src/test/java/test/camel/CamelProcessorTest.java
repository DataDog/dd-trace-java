package test.camel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import datadog.context.Context;
import datadog.trace.instrumentation.camel42.CamelTracingProcessor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.camel.AsyncCallback;
import org.apache.camel.Exchange;
import org.apache.camel.impl.DefaultCamelContext;
import org.apache.camel.support.AsyncProcessorSupport;
import org.apache.camel.support.DefaultExchange;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CamelProcessorTest extends AbstractCamelTest {
  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void delegatesTheOriginalCallbackWithoutRouteState(boolean synchronous) throws Exception {
    AtomicReference<AsyncCallback> received = new AtomicReference<>();
    AtomicInteger completions = new AtomicInteger();
    AsyncCallback callback =
        doneSync -> {
          assertEquals(synchronous, doneSync);
          completions.incrementAndGet();
        };
    Context original = Context.current();
    try (DefaultCamelContext context = new DefaultCamelContext()) {
      Exchange exchange = new DefaultExchange(context);
      CamelTracingProcessor processor =
          new CamelTracingProcessor(
              new AsyncProcessorSupport() {
                @Override
                public boolean process(Exchange actual, AsyncCallback done) {
                  assertSame(exchange, actual);
                  assertSame(original, Context.current());
                  received.set(done);
                  if (synchronous) {
                    done.done(true);
                  }
                  return synchronous;
                }
              });
      assertEquals(synchronous, processor.process(exchange, callback));
      assertSame(callback, received.get(), "a no-state exchange must retain its original callback");
      assertSame(original, Context.current());
      if (!synchronous) {
        received.get().done(false);
      }
      assertEquals(1, completions.get());
      assertSame(original, Context.current());
    }
  }

  @Test
  void preservesProcessorFailureWithoutRouteState() throws Exception {
    RuntimeException failure = new IllegalStateException("processor failure");
    AtomicInteger completions = new AtomicInteger();
    Context original = Context.current();
    try (DefaultCamelContext context = new DefaultCamelContext()) {
      CamelTracingProcessor processor =
          new CamelTracingProcessor(
              new AsyncProcessorSupport() {
                @Override
                public boolean process(Exchange exchange, AsyncCallback callback) {
                  throw failure;
                }
              });
      assertSame(
          failure,
          assertThrows(
              IllegalStateException.class,
              () ->
                  processor.process(
                      new DefaultExchange(context), ignored -> completions.incrementAndGet())));
      assertEquals(0, completions.get());
      assertSame(original, Context.current());
    }
  }
}
