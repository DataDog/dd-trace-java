package datadog.trace.instrumentation.aerospike4;

import com.aerospike.client.AerospikeException;
import com.aerospike.client.Key;
import com.aerospike.client.listener.ExistsSequenceListener;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.context.TraceScope;

public final class TracingExistsSequenceListener
    extends AbstractTracingListener<ExistsSequenceListener> implements ExistsSequenceListener {

  public TracingExistsSequenceListener(
      final AgentScope clientScope, final ExistsSequenceListener listener) {
    super(clientScope, listener);
  }

  @Override
  public void onExists(final Key key, final boolean exists) {
    listener.onExists(key, exists);
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
