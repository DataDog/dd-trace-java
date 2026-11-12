package datadog.trace.instrumentation.aerospike4;

import static datadog.trace.agent.test.assertions.SpanMatcher.span;
import static datadog.trace.agent.test.assertions.TraceMatcher.trace;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.aerospike.client.BatchRead;
import com.aerospike.client.Key;
import com.aerospike.client.Record;
import com.aerospike.client.listener.BatchSequenceListener;
import com.aerospike.client.listener.ExistsSequenceListener;
import com.aerospike.client.listener.RecordSequenceListener;
import datadog.context.Context;
import datadog.context.ContextContinuation;
import datadog.context.ContextKey;
import datadog.context.ContextScope;
import datadog.trace.agent.test.AbstractInstrumentationTest;
import datadog.trace.agent.test.scopediag.TrackScopeContinuations;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.core.DDSpan;
import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@TrackScopeContinuations
class TracingListenerTest extends AbstractInstrumentationTest {
  private final Key key = new Key("test", "stream", "record");
  private final Record record = new Record(Collections.emptyMap(), 1, 1);
  private final BatchRead batch = new BatchRead(key, true);

  @ParameterizedTest
  @ValueSource(ints = {0, 1, 2})
  void repeatedRecordsRestoreContextAndPreserveTerminalOwnership(int callback) {
    Fixture fixture = new Fixture();
    AgentSpan operation = fixture.operation;
    Object marker = new Object();
    CountingContinuation continuation;
    try (ContextScope caller = operation.with(fixture.markerKey, marker).attach()) {
      continuation = new CountingContinuation(operation.captureWithContext());
    }
    AtomicInteger calls = new AtomicInteger();
    RuntimeException failure = new IllegalStateException("delegate failure");
    Object delegate =
        Proxy.newProxyInstance(
            getClass().getClassLoader(),
            new Class<?>[] {
              ExistsSequenceListener.class,
              RecordSequenceListener.class,
              BatchSequenceListener.class
            },
            (proxy, method, args) -> {
              assertSame(fixture.operation, activeSpan());
              assertSame(marker, Context.current().get(fixture.markerKey));
              if (method.getName().equals("onExists")) {
                assertSame(key, args[0]);
                assertEquals(true, args[1]);
              } else if (args.length == 2) {
                assertSame(key, args[0]);
                assertSame(record, args[1]);
              } else {
                assertSame(batch, args[0]);
              }
              if (calls.incrementAndGet() == 3) {
                throw failure;
              }
              return null;
            });
    TracingListener listener = new TracingListener(operation, continuation, delegate);
    Context foreign = Context.root().with(fixture.markerKey, new Object());
    try (ContextScope eventLoop = foreign.attach()) {
      try {
        for (int i = 0; i < 2; i++) {
          invoke(listener, callback);
          assertSame(foreign, Context.current());
          assertFalse(((DDSpan) operation).isFinished());
          assertEquals(0, continuation.resumes);
          assertEquals(0, continuation.releases);
        }
        assertSame(failure, assertThrows(RuntimeException.class, () -> invoke(listener, callback)));
        assertSame(foreign, Context.current());
        assertFalse(((DDSpan) operation).isFinished());
        assertEquals(3, calls.get());
        assertEquals(0, continuation.resumes);
        assertEquals(0, continuation.releases);
      } finally {
        new TracingListener(operation, continuation, null).onSuccess();
      }
      assertSame(foreign, Context.current());
    }
    assertEquals(1, continuation.releases);
    assertTraces(trace(span().root().operationName("stream")));
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 1, 2})
  void nullDelegateLeavesTerminalOwnershipUntouched(int callback) {
    ContextKey<Object> markerKey = ContextKey.named("aerospike-stream-test");
    AgentSpan operation = startSpan("test", "stream");
    CountingContinuation continuation = new CountingContinuation(operation.captureWithContext());
    TracingListener listener = new TracingListener(operation, continuation, null);
    Context foreign = Context.root().with(markerKey, new Object());
    try (ContextScope scope = foreign.attach()) {
      try {
        invoke(listener, callback);
        invoke(listener, callback);
        assertSame(foreign, Context.current());
        assertFalse(((DDSpan) operation).isFinished());
        assertEquals(0, continuation.resumes);
        assertEquals(0, continuation.releases);
      } finally {
        listener.onSuccess();
      }
    }
    assertEquals(1, continuation.releases);
    assertTraces(trace(span().root().operationName("stream")));
  }

  private void invoke(TracingListener listener, int callback) {
    if (callback == 0) listener.onExists(key, true);
    else if (callback == 1) listener.onRecord(key, record);
    else listener.onRecord(batch);
  }

  private static final class Fixture {
    private final ContextKey<Object> markerKey = ContextKey.named("aerospike-stream-test");
    private final AgentSpan operation = startSpan("test", "stream");
  }

  private static final class CountingContinuation implements ContextContinuation {
    private final ContextContinuation delegate;
    private int resumes;
    private int releases;

    private CountingContinuation(ContextContinuation delegate) {
      this.delegate = delegate;
    }

    @Override
    public ContextContinuation hold() {
      delegate.hold();
      return this;
    }

    @Override
    public Context context() {
      return delegate.context();
    }

    @Override
    public ContextScope resume() {
      resumes++;
      return delegate.resume();
    }

    @Override
    public void release() {
      releases++;
      delegate.release();
    }
  }
}
