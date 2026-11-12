package datadog.trace.instrumentation.aerospike6;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyByte;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.aerospike.client.AerospikeException;
import com.aerospike.client.BatchRecord;
import com.aerospike.client.Key;
import com.aerospike.client.ResultCode;
import com.aerospike.client.listener.BatchOperateListListener;
import com.aerospike.client.listener.BatchRecordArrayListener;
import com.aerospike.client.listener.BatchRecordSequenceListener;
import datadog.appsec.api.blocking.BlockingException;
import datadog.context.Context;
import datadog.context.ContextContinuation;
import datadog.context.ContextKey;
import datadog.context.ContextScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.NoopContinuation;
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
  static Stream<Arguments> terminalCallbacksArguments() {
    BatchRecord[] records = {
      new BatchRecord(new Key("test", "set", "present"), false),
      new BatchRecord(new Key("test", "set", "missing"), false)
    };
    records[0].resultCode = ResultCode.OK;
    records[1].setError(ResultCode.KEY_NOT_FOUND_ERROR, false);
    AerospikeException error = new AerospikeException("batch failed");
    return Stream.of(
        Arguments.of(TerminalCallback.ARRAY_SUCCESS, new Object[] {records, false}),
        Arguments.of(TerminalCallback.ARRAY_FAILURE, new Object[] {records, error}),
        Arguments.of(TerminalCallback.LIST_SUCCESS, new Object[] {Arrays.asList(records), false}),
        Arguments.of(TerminalCallback.LIST_FAILURE, new Object[] {error}),
        Arguments.of(TerminalCallback.SEQUENCE_SUCCESS, new Object[0]),
        Arguments.of(TerminalCallback.SEQUENCE_FAILURE, new Object[] {error}));
  }

  @ParameterizedTest
  @MethodSource("terminalCallbacksArguments")
  void terminalCallbacksPreserveArgumentsAndContext(TerminalCallback callback, Object[] expected) {
    Fixture fixture = new Fixture();
    AtomicInteger calls = new AtomicInteger();
    Object listener =
        mock(
            callback.type,
            invocation -> {
              fixture.assertCapturedContext();
              verify(fixture.span).finish();
              Object[] args = invocation.getArguments();
              assertEquals(expected.length, args.length);
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
    BatchTracingListener wrapped = fixture.wrap(listener);
    try (ContextScope ignored = fixture.foreign.attach()) {
      callback.invoke(wrapped, expected);
      assertSame(fixture.foreign, Context.current());
    }
    assertEquals(1, calls.get());
    fixture.assertCompleted(1, 0);
    if (callback.name().endsWith("FAILURE")) {
      verify(fixture.span).addThrowable(same((Throwable) expected[expected.length - 1]), anyByte());
    } else {
      verify(fixture.span, never()).addThrowable(any(Throwable.class), anyByte());
      verify(fixture.span, never()).setError(true);
    }
  }

  @ParameterizedTest
  @MethodSource("terminalCallbacksArguments")
  void nullDelegatesReleaseTheCapture(TerminalCallback callback, Object[] args) {
    Fixture fixture = new Fixture();
    try (ContextScope ignored = fixture.foreign.attach()) {
      callback.invoke(fixture.wrap(null), args);
      assertSame(fixture.foreign, Context.current());
    }
    fixture.assertCompleted(0, 1);
  }

  @ParameterizedTest
  @MethodSource("terminalCallbacksArguments")
  void terminalCallbackExceptionsKeepTheirIdentity(TerminalCallback callback, Object[] args) {
    Fixture fixture = new Fixture();
    IllegalStateException thrown = new IllegalStateException("delegate failed");
    Object listener =
        mock(
            callback.type,
            invocation -> {
              fixture.assertCapturedContext();
              throw thrown;
            });
    BatchTracingListener wrapped = fixture.wrap(listener);
    try (ContextScope ignored = fixture.foreign.attach()) {
      assertSame(
          thrown, assertThrows(IllegalStateException.class, () -> callback.invoke(wrapped, args)));
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
    BatchRecordSequenceListener wrapped = fixture.wrap(listener);
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
    BatchRecordSequenceListener listener =
        mock(
            BatchRecordSequenceListener.class,
            invocation -> {
              Object[] args = invocation.getArguments();
              assertSame(ambient, Context.current());
              assertSame(foreignSpan, AgentSpan.fromContext(Context.current()));
              assertEquals("foreign", Context.current().get(marker));
              calls.incrementAndGet();
              if (throwsOnLastRecord && args.length == 2 && (int) args[1] == 2) {
                throw thrown;
              }
              return null;
            });
    BatchRecordSequenceListener wrapped =
        new BatchTracingListener(span, NoopContinuation.INSTANCE, listener);
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
    IllegalStateException error = new IllegalStateException("listener setup failed");
    try (ContextScope ignored = fixture.foreign.attach()) {
      ContextScope submission = fixture.captured.attach();
      BatchTracingListener.closeSubmission(
          submission, null, fixture.continuation, fixture.span, error, true);
      assertSame(fixture.foreign, Context.current());
    }
    fixture.assertCompleted(0, 1);
    verify(fixture.span).addThrowable(same(error), anyByte());
  }

  @Test
  void combinedDelegateExposesEveryTypedCallbackIncludingSharedFailure() {
    Fixture fixture = new Fixture();
    CombinedListener listener =
        mock(
            CombinedListener.class,
            invocation -> {
              fixture.assertCapturedContext();
              return null;
            });
    BatchTracingListener wrapped = fixture.wrap(listener);
    BatchRecord record = new BatchRecord(new Key("test", "set", "key"), false);
    BatchRecord[] records = {record};
    List<BatchRecord> list = Arrays.asList(records);
    AerospikeException error = new AerospikeException("batch failed");
    try (ContextScope ignored = fixture.foreign.attach()) {
      wrapped.onRecord(record, 7);
      wrapped.onSuccess(records, false);
      wrapped.onSuccess(list, false);
      wrapped.onSuccess();
      wrapped.onFailure(records, error);
      ((BatchOperateListListener) wrapped).onFailure(error);
      ((BatchRecordSequenceListener) wrapped).onFailure(error);
      assertSame(fixture.foreign, Context.current());
    }
    verify(listener).onRecord(same(record), eq(7));
    verify(listener).onSuccess(same(records), eq(false));
    verify(listener).onSuccess(same(list), eq(false));
    verify(listener).onSuccess();
    verify(listener).onFailure(same(records), same(error));
    verify(listener, times(2)).onFailure(same(error));
    verify(fixture.span, never()).addThrowable(any(Throwable.class), anyByte());
    fixture.assertCompleted(1, 0);
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void firstTerminalOwnsCompletionBeforeDuplicateCallbackAndSubmissionAbort(boolean failureFirst) {
    Fixture fixture = new Fixture();
    AtomicInteger calls = new AtomicInteger();
    BatchRecordSequenceListener listener =
        mock(
            BatchRecordSequenceListener.class,
            invocation -> {
              fixture.assertCapturedContext();
              verify(fixture.span).finish();
              calls.incrementAndGet();
              return null;
            });
    BatchTracingListener wrapped = fixture.wrap(listener);
    AerospikeException error = new AerospikeException("batch failed");
    try (ContextScope ignored = fixture.foreign.attach()) {
      if (failureFirst) {
        wrapped.onFailure(error);
        wrapped.onSuccess();
      } else {
        wrapped.onSuccess();
        wrapped.onFailure(error);
      }
      ContextScope submission = fixture.captured.attach();
      BatchTracingListener.closeSubmission(
          submission, wrapped, fixture.continuation, fixture.span, error, true);
      assertSame(fixture.foreign, Context.current());
    }
    assertEquals(2, calls.get());
    fixture.assertCompleted(1, 0);
    verify(fixture.span, times(failureFirst ? 1 : 0)).addThrowable(same(error), anyByte());
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void throwingErrorDecorationStillFinishesAndResumesOrReleasesCapture(boolean nullDelegate) {
    Fixture fixture = new Fixture();
    AerospikeException error = new AerospikeException("batch failed");
    doThrow(new BlockingException("decoration failed"))
        .when(fixture.span)
        .addThrowable(same(error), anyByte());
    BatchRecordSequenceListener listener =
        mock(
            BatchRecordSequenceListener.class,
            invocation -> {
              fixture.assertCapturedContext();
              verify(fixture.span).finish();
              return null;
            });
    try (ContextScope ignored = fixture.foreign.attach()) {
      fixture.wrap(nullDelegate ? null : listener).onFailure(error);
      assertSame(fixture.foreign, Context.current());
    }
    if (nullDelegate) {
      verifyNoInteractions(listener);
    } else {
      verify(listener).onFailure(same(error));
    }
    fixture.assertCompleted(nullDelegate ? 0 : 1, nullDelegate ? 1 : 0);
  }

  interface CombinedListener
      extends BatchRecordArrayListener, BatchOperateListListener, BatchRecordSequenceListener {}

  private enum TerminalCallback {
    ARRAY_SUCCESS(BatchRecordArrayListener.class),
    ARRAY_FAILURE(BatchRecordArrayListener.class),
    LIST_SUCCESS(BatchOperateListListener.class),
    LIST_FAILURE(BatchOperateListListener.class),
    SEQUENCE_SUCCESS(BatchRecordSequenceListener.class),
    SEQUENCE_FAILURE(BatchRecordSequenceListener.class);

    final Class<?> type;

    TerminalCallback(Class<?> type) {
      this.type = type;
    }

    @SuppressWarnings("unchecked")
    void invoke(BatchTracingListener listener, Object[] args) {
      switch (this) {
        case ARRAY_SUCCESS:
          listener.onSuccess((BatchRecord[]) args[0], (boolean) args[1]);
          break;
        case ARRAY_FAILURE:
          listener.onFailure((BatchRecord[]) args[0], (AerospikeException) args[1]);
          break;
        case LIST_SUCCESS:
          listener.onSuccess((List<BatchRecord>) args[0], (boolean) args[1]);
          break;
        case LIST_FAILURE:
          ((BatchOperateListListener) listener).onFailure((AerospikeException) args[0]);
          break;
        case SEQUENCE_SUCCESS:
          listener.onSuccess();
          break;
        case SEQUENCE_FAILURE:
          ((BatchRecordSequenceListener) listener).onFailure((AerospikeException) args[0]);
          break;
      }
    }
  }

  private static class Fixture {
    private static final ContextKey<String> MARKER = ContextKey.named("batch-listener-marker");
    final AgentSpan span = mock(AgentSpan.class, CALLS_REAL_METHODS);
    final Context captured = Context.root().with(span).with(MARKER, "captured");
    final Context foreign = Context.root().with(MARKER, "foreign");
    final CountingContinuation continuation = new CountingContinuation(captured.capture());

    BatchTracingListener wrap(Object listener) {
      return new BatchTracingListener(span, continuation, listener);
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
