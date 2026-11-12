package datadog.trace.instrumentation.aerospike4;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyByte;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.aerospike.client.AerospikeException;
import com.aerospike.client.BatchRecord;
import com.aerospike.client.Key;
import com.aerospike.client.ResultCode;
import com.aerospike.client.listener.BatchOperateListListener;
import com.aerospike.client.listener.BatchRecordArrayListener;
import com.aerospike.client.listener.BatchRecordSequenceListener;
import datadog.context.Context;
import datadog.context.ContextContinuation;
import datadog.context.ContextKey;
import datadog.context.ContextScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.NoopContinuation;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class BatchTracingListenerTest {
  static Stream<Arguments> terminalCallbacksArguments() throws Exception {
    BatchRecord[] records = {
      new BatchRecord(new Key("test", "set", "present"), false),
      new BatchRecord(new Key("test", "set", "missing"), false)
    };
    records[0].resultCode = ResultCode.OK;
    records[1].setError(ResultCode.KEY_NOT_FOUND_ERROR, false);
    AerospikeException error = new AerospikeException("batch failed");
    return Stream.of(
        Arguments.of(
            BatchRecordArrayListener.class.getMethod(
                "onSuccess", BatchRecord[].class, boolean.class),
            new Object[] {records, false}),
        Arguments.of(
            BatchRecordArrayListener.class.getMethod(
                "onFailure", BatchRecord[].class, AerospikeException.class),
            new Object[] {records, error}),
        Arguments.of(
            BatchOperateListListener.class.getMethod("onSuccess", List.class, boolean.class),
            new Object[] {Arrays.asList(records), false}),
        Arguments.of(
            BatchOperateListListener.class.getMethod("onFailure", AerospikeException.class),
            new Object[] {error}),
        Arguments.of(BatchRecordSequenceListener.class.getMethod("onSuccess"), new Object[0]),
        Arguments.of(
            BatchRecordSequenceListener.class.getMethod("onFailure", AerospikeException.class),
            new Object[] {error}));
  }

  @ParameterizedTest
  @MethodSource("terminalCallbacksArguments")
  void terminalCallbacksPreserveArgumentsAndContext(Method method, Object[] expected)
      throws Throwable {
    Fixture fixture = new Fixture();
    AtomicInteger calls = new AtomicInteger();
    Object listener =
        proxy(
            method.getDeclaringClass(),
            (proxy, callback, args) -> {
              fixture.assertCapturedContext();
              assertEquals(method, callback);
              assertEquals(expected.length, args == null ? 0 : args.length);
              for (int i = 0; i < expected.length; i++) {
                if (expected[i] instanceof Boolean) {
                  assertEquals(expected[i], args[i]);
                } else {
                  assertSame(expected[i], args[i]);
                }
              }
              calls.incrementAndGet();
              return null;
            });
    Object wrapped = fixture.wrap(method.getDeclaringClass(), listener);
    assertSame(method.getDeclaringClass().getClassLoader(), wrapped.getClass().getClassLoader());
    try (ContextScope ignored = fixture.foreign.attach()) {
      invoke(method, wrapped, expected);
      assertSame(fixture.foreign, Context.current());
    }
    assertEquals(1, calls.get());
    fixture.assertCompleted(1, 0);
    if (method.getName().equals("onFailure")) {
      verify(fixture.span).addThrowable(same((Throwable) expected[expected.length - 1]), anyByte());
    } else {
      verify(fixture.span, never()).addThrowable(any(Throwable.class), anyByte());
      verify(fixture.span, never()).setError(true);
    }
  }

  @ParameterizedTest
  @MethodSource("terminalCallbacksArguments")
  void nullDelegatesReleaseTheCapture(Method method, Object[] args) throws Throwable {
    Fixture fixture = new Fixture();
    try (ContextScope ignored = fixture.foreign.attach()) {
      invoke(method, fixture.wrap(method.getDeclaringClass(), null), args);
      assertSame(fixture.foreign, Context.current());
    }
    fixture.assertCompleted(0, 1);
  }

  @ParameterizedTest
  @MethodSource("terminalCallbacksArguments")
  void terminalCallbackExceptionsKeepTheirIdentity(Method method, Object[] args) {
    Fixture fixture = new Fixture();
    IllegalStateException thrown = new IllegalStateException("delegate failed");
    Object listener =
        proxy(
            method.getDeclaringClass(),
            (proxy, callback, arguments) -> {
              fixture.assertCapturedContext();
              throw thrown;
            });
    Object wrapped = fixture.wrap(method.getDeclaringClass(), listener);
    try (ContextScope ignored = fixture.foreign.attach()) {
      assertSame(
          thrown, assertThrows(IllegalStateException.class, () -> invoke(method, wrapped, args)));
      assertSame(fixture.foreign, Context.current());
    }
    fixture.assertCompleted(1, 0);
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void repeatedRecordsAttachFullContextWithoutConsumingCapture(boolean throwsOnLastRecord) {
    Fixture fixture = new Fixture();
    BatchRecord record = new BatchRecord(new Key("test", "set", "key"), false);
    AtomicInteger calls = new AtomicInteger();
    IllegalStateException thrown = new IllegalStateException("record failed");
    BatchRecordSequenceListener listener =
        new BatchRecordSequenceListener() {
          @Override
          public void onRecord(BatchRecord actual, int index) {
            fixture.assertCapturedContext();
            assertSame(record, actual);
            assertEquals(calls.getAndIncrement(), index);
            if (throwsOnLastRecord && index == 2) {
              throw thrown;
            }
          }

          @Override
          public void onSuccess() {
            fixture.assertCapturedContext();
          }

          @Override
          public void onFailure(AerospikeException exception) {
            throw new AssertionError(exception);
          }
        };
    BatchRecordSequenceListener wrapped =
        (BatchRecordSequenceListener) fixture.wrap(BatchRecordSequenceListener.class, listener);
    try (ContextScope ignored = fixture.foreign.attach()) {
      for (int index = 0; index < 3; index++) {
        final int current = index;
        if (throwsOnLastRecord && index == 2) {
          assertSame(
              thrown,
              assertThrows(IllegalStateException.class, () -> wrapped.onRecord(record, current)));
        } else {
          wrapped.onRecord(record, index);
        }
        assertSame(fixture.foreign, Context.current());
        assertEquals(0, fixture.continuation.resumes);
        assertEquals(0, fixture.continuation.releases);
        verify(fixture.span, never()).finish();
      }
      wrapped.onSuccess();
      assertSame(fixture.foreign, Context.current());
    }
    assertEquals(3, calls.get());
    fixture.assertCompleted(1, 0);
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void noopCapturePreservesAmbientContextForRecordsAndRepeatedTerminals(
      boolean throwsOnLastRecord) {
    AgentSpan span = mock(AgentSpan.class, CALLS_REAL_METHODS);
    AgentSpan foreignSpan = mock(AgentSpan.class, CALLS_REAL_METHODS);
    ContextKey<String> marker = ContextKey.named("batch-noop-marker");
    Context ambient = Context.root().with(foreignSpan).with(marker, "foreign");
    BatchRecord record = new BatchRecord(new Key("test", "set", "key"), false);
    AtomicInteger calls = new AtomicInteger();
    IllegalStateException thrown = new IllegalStateException("record failed");
    Object listener =
        proxy(
            BatchRecordSequenceListener.class,
            (proxy, method, args) -> {
              assertSame(ambient, Context.current());
              assertSame(foreignSpan, AgentSpan.fromContext(Context.current()));
              assertEquals("foreign", Context.current().get(marker));
              calls.incrementAndGet();
              if (throwsOnLastRecord && method.getName().equals("onRecord") && (int) args[1] == 2) {
                throw thrown;
              }
              return null;
            });
    BatchRecordSequenceListener wrapped =
        (BatchRecordSequenceListener)
            new BatchTracingListener(span, NoopContinuation.INSTANCE, listener)
                .proxy(BatchRecordSequenceListener.class);
    try (ContextScope ignored = ambient.attach()) {
      wrapped.onRecord(record, 0);
      wrapped.onRecord(record, 1);
      if (throwsOnLastRecord) {
        assertSame(
            thrown, assertThrows(IllegalStateException.class, () -> wrapped.onRecord(record, 2)));
      } else {
        wrapped.onRecord(record, 2);
      }
      assertSame(ambient, Context.current());
      verify(span, never()).finish();
      wrapped.onSuccess();
      assertSame(ambient, Context.current());
      wrapped.onSuccess();
      assertSame(ambient, Context.current());
    }
    assertEquals(5, calls.get());
    verify(span).finish();
  }

  @Test
  void abortedSubmissionRestoresContextAndCompletesOnlyOnceWithoutCallback() {
    Fixture fixture = new Fixture();
    BatchRecordArrayListener listener = mock(BatchRecordArrayListener.class);
    BatchTracingListener batch =
        new BatchTracingListener(fixture.span, fixture.continuation, listener);
    IllegalStateException error = new IllegalStateException("submission failed");
    try (ContextScope ignored = fixture.foreign.attach()) {
      ContextScope submission = fixture.captured.attach();
      BatchTracingListener.closeSubmission(
          submission, batch, fixture.continuation, fixture.span, error, true);
      assertSame(fixture.foreign, Context.current());
      fixture.assertCompleted(0, 1);
      BatchTracingListener.closeSubmission(
          null, batch, fixture.continuation, fixture.span, error, true);
      assertSame(fixture.foreign, Context.current());
    }
    fixture.assertCompleted(0, 1);
    verify(fixture.span).addThrowable(same(error), anyByte());
    verifyNoInteractions(listener);
  }

  @Test
  void abortedPartialSetupWithoutHandlerReleasesCaptureAndRestoresContext() {
    Fixture fixture = new Fixture();
    IllegalStateException error = new IllegalStateException("proxy setup failed");
    try (ContextScope ignored = fixture.foreign.attach()) {
      ContextScope submission = fixture.captured.attach();
      BatchTracingListener.closeSubmission(
          submission, null, fixture.continuation, fixture.span, error, true);
      assertSame(fixture.foreign, Context.current());
    }
    fixture.assertCompleted(0, 1);
    verify(fixture.span).addThrowable(same(error), anyByte());
  }

  private static Object proxy(Class<?> type, InvocationHandler handler) {
    return Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, handler);
  }

  private static void invoke(Method method, Object listener, Object[] args) throws Throwable {
    try {
      method.invoke(listener, args);
    } catch (InvocationTargetException exception) {
      throw exception.getCause();
    }
  }

  private static class Fixture {
    private static final ContextKey<String> MARKER = ContextKey.named("batch-listener-marker");
    final AgentSpan span = mock(AgentSpan.class, CALLS_REAL_METHODS);
    final Context captured = Context.root().with(span).with(MARKER, "captured");
    final Context foreign = Context.root().with(MARKER, "foreign");
    final CountingContinuation continuation = new CountingContinuation(captured.capture());

    Object wrap(Class<?> type, Object listener) {
      BatchTracingListener.validate(type);
      return new BatchTracingListener(span, continuation, listener).proxy(type);
    }

    void assertCapturedContext() {
      assertEquals("captured", Context.current().get(MARKER));
      assertSame(span, AgentSpan.fromContext(Context.current()));
    }

    void assertCompleted(int resumes, int releases) {
      verify(span).finish();
      assertEquals(resumes, continuation.resumes);
      assertEquals(releases, continuation.releases);
    }
  }

  private static class CountingContinuation implements ContextContinuation {
    final ContextContinuation delegate;
    int resumes;
    int releases;

    CountingContinuation(ContextContinuation delegate) {
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
