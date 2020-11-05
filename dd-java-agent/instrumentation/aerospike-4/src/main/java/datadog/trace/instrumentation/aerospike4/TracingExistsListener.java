package datadog.trace.instrumentation.aerospike4;

import com.aerospike.client.AerospikeException;
import com.aerospike.client.Key;
import com.aerospike.client.listener.ExistsListener;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.context.TraceScope;

public final class TracingExistsListener extends AbstractTracingListener<ExistsListener>
    implements ExistsListener {

  public TracingExistsListener(final AgentScope clientScope, final ExistsListener listener) {
    super(clientScope, listener);
  }

  @Override
  public void onSuccess(final Key key, final boolean exists) {
    super.onSuccess();

    if (listener != null) {
      try (final TraceScope scope = continuation.activate()) {
        listener.onSuccess(key, exists);
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
