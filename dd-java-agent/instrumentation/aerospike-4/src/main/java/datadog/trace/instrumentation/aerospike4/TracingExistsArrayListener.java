package datadog.trace.instrumentation.aerospike4;

import com.aerospike.client.AerospikeException;
import com.aerospike.client.Key;
import com.aerospike.client.listener.ExistsArrayListener;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.context.TraceScope;

public final class TracingExistsArrayListener extends AbstractTracingListener<ExistsArrayListener>
    implements ExistsArrayListener {

  public TracingExistsArrayListener(
      final AgentScope clientScope, final ExistsArrayListener listener) {
    super(clientScope, listener);
  }

  @Override
  public void onSuccess(final Key[] keys, final boolean[] exists) {
    super.onSuccess();

    if (listener != null) {
      try (final TraceScope scope = continuation.activate()) {
        listener.onSuccess(keys, exists);
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
