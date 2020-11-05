package datadog.trace.instrumentation.aerospike4;

import com.aerospike.client.AerospikeException;
import com.aerospike.client.Key;
import com.aerospike.client.Record;
import com.aerospike.client.listener.RecordSequenceListener;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.context.TraceScope;

public final class TracingRecordSequenceListener
    extends AbstractTracingListener<RecordSequenceListener> implements RecordSequenceListener {

  public TracingRecordSequenceListener(
      final AgentScope clientScope, final RecordSequenceListener listener) {
    super(clientScope, listener);
  }

  @Override
  public void onRecord(final Key key, final Record record) {
    listener.onRecord(key, record);
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
