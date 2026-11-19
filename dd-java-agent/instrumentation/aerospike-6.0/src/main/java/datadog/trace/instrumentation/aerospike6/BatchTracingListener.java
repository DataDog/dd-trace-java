package datadog.trace.instrumentation.aerospike6;

import static datadog.trace.instrumentation.aerospike4.AerospikeClientDecorator.DECORATE;

import com.aerospike.client.AerospikeException;
import com.aerospike.client.BatchRecord;
import com.aerospike.client.listener.BatchOperateListListener;
import com.aerospike.client.listener.BatchRecordArrayListener;
import com.aerospike.client.listener.BatchRecordSequenceListener;
import datadog.context.ContextContinuation;
import datadog.context.ContextScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.NoopContinuation;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/** Traces batch completion while preserving each listener's callback contract. */
public final class BatchTracingListener
    implements BatchRecordArrayListener, BatchRecordSequenceListener, BatchOperateListListener {
  private final AgentSpan clientSpan;
  private final ContextContinuation continuation;
  private final Object listener;
  private final AtomicBoolean completed = new AtomicBoolean();

  public BatchTracingListener(
      final AgentSpan clientSpan, final ContextContinuation continuation, final Object listener) {
    this.clientSpan = clientSpan;
    this.continuation = continuation;
    this.listener = listener;
  }

  @Override
  public void onSuccess(final BatchRecord[] records, final boolean status) {
    try (final ContextScope scope = terminalScope(null)) {
      if (listener != null) {
        ((BatchRecordArrayListener) listener).onSuccess(records, status);
      }
    }
  }

  @Override
  public void onFailure(final BatchRecord[] records, final AerospikeException error) {
    try (final ContextScope scope = terminalScope(error)) {
      if (listener != null) {
        ((BatchRecordArrayListener) listener).onFailure(records, error);
      }
    }
  }

  @Override
  public void onSuccess(final List<BatchRecord> records, final boolean status) {
    try (final ContextScope scope = terminalScope(null)) {
      if (listener != null) {
        ((BatchOperateListListener) listener).onSuccess(records, status);
      }
    }
  }

  @Override
  public void onRecord(final BatchRecord record, final int index) {
    if (listener != null) {
      try (final ContextScope scope = attachContext()) {
        ((BatchRecordSequenceListener) listener).onRecord(record, index);
      }
    }
  }

  @Override
  public void onSuccess() {
    try (final ContextScope scope = terminalScope(null)) {
      if (listener != null) {
        ((BatchRecordSequenceListener) listener).onSuccess();
      }
    }
  }

  @Override
  public void onFailure(final AerospikeException error) {
    try (final ContextScope scope = terminalScope(error)) {
      if (listener instanceof BatchOperateListListener) {
        // A combined delegate has one implementation of this shared failure signature.
        ((BatchOperateListListener) listener).onFailure(error);
      } else if (listener != null) {
        ((BatchRecordSequenceListener) listener).onFailure(error);
      }
    }
  }

  private ContextScope terminalScope(final Throwable error) {
    final boolean terminalOwner = completed.compareAndSet(false, true);
    if (terminalOwner) {
      try {
        try {
          if (error != null) {
            DECORATE.onError(clientSpan, error);
          }
          DECORATE.beforeFinish(clientSpan);
        } finally {
          clientSpan.finish();
        }
      } catch (final Throwable ignored) {
        // Callback delivery still owns the captured context if decoration fails.
      }
    }
    if (listener == null) {
      if (terminalOwner) {
        continuation.release();
      }
      return null;
    }
    return terminalOwner ? continuation.resume() : attachContext();
  }

  private ContextScope attachContext() {
    return continuation == NoopContinuation.INSTANCE
        ? continuation.resume()
        : continuation.context().attach();
  }

  public static void closeSubmission(
      final ContextScope scope,
      final BatchTracingListener batch,
      final ContextContinuation continuation,
      final AgentSpan span,
      final Throwable error,
      final boolean abort) {
    final boolean finishSpan =
        abort && span != null && (batch == null || batch.completed.compareAndSet(false, true));
    try {
      if (finishSpan) {
        if (error != null) {
          DECORATE.onError(span, error);
        }
        DECORATE.beforeFinish(span);
      }
    } finally {
      try {
        if (scope != null) {
          scope.close();
        }
      } finally {
        if (finishSpan) {
          try {
            span.finish();
          } finally {
            if (continuation != null) {
              continuation.release();
            }
          }
        }
      }
    }
  }
}
