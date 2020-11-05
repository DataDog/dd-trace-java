package datadog.trace.instrumentation.aerospike4;

import com.aerospike.client.AerospikeException;
import com.aerospike.client.BatchRead;
import com.aerospike.client.listener.BatchSequenceListener;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.context.TraceScope;

public final class TracingBatchSequenceListener
    extends AbstractTracingListener<BatchSequenceListener> implements BatchSequenceListener {

  public TracingBatchSequenceListener(
      final AgentScope clientScope, final BatchSequenceListener listener) {
    super(clientScope, listener);
  }

  @Override
  public void onRecord(final BatchRead record) {
    listener.onRecord(record);
  }

  @Override
  public void onSuccess() {
    super.onSuccess();

    if (listener != null) {
      try (final TraceScope scope = continuation.activate()) {
        listener.onSuccess();
      }
    } else {
      continuation.cancel();
    }
  }

  @Override
  public void onFailure(final AerospikeException cause) {
    super.onFailure(cause);

    if (listener != null) {
      try (final TraceScope scope = continuation.activate()) {
        listener.onFailure(cause);
      }
    } else {
      continuation.cancel();
    }
  }
}
