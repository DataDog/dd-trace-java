package datadog.trace.instrumentation.aerospike4;

import com.aerospike.client.AerospikeException;
import com.aerospike.client.Key;
import com.aerospike.client.listener.WriteListener;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.context.TraceScope;

public final class TracingWriteListener extends AbstractTracingListener<WriteListener>
    implements WriteListener {

  public TracingWriteListener(final AgentScope clientScope, final WriteListener listener) {
    super(clientScope, listener);
  }

  @Override
  public void onSuccess(final Key key) {
    super.onSuccess();

    if (listener != null) {
      try (final TraceScope scope = continuation.activate()) {
        listener.onSuccess(key);
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
