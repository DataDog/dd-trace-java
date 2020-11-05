package datadog.trace.instrumentation.aerospike4;

import com.aerospike.client.listener.BatchListListener;
import com.aerospike.client.listener.BatchSequenceListener;
import com.aerospike.client.listener.DeleteListener;
import com.aerospike.client.listener.ExecuteListener;
import com.aerospike.client.listener.ExistsArrayListener;
import com.aerospike.client.listener.ExistsListener;
import com.aerospike.client.listener.ExistsSequenceListener;
import com.aerospike.client.listener.RecordArrayListener;
import com.aerospike.client.listener.RecordListener;
import com.aerospike.client.listener.RecordSequenceListener;
import com.aerospike.client.listener.WriteListener;
import datadog.trace.api.Function;
import datadog.trace.api.cache.DDCache;
import datadog.trace.api.cache.DDCaches;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;

public final class TracingListenerHelper implements Function<String, String> {
  public static final TracingListenerHelper INSTANCE = new TracingListenerHelper();

  // won't grow more than the number of AerospikeClient methods that use a listener
  private static final DDCache<String, String> SIGNATURE_TO_LISTENER_NAME =
      DDCaches.newUnboundedCache(16);

  private static final String LISTENER_PKG_PREFIX = "com.aerospike.client.listener.";

  public Object traceListener(
      final String signature, final AgentScope scope, final Object listener) {

    final String listenerName = SIGNATURE_TO_LISTENER_NAME.computeIfAbsent(signature, this);

    switch (listenerName) {
      case "BatchListListener":
        return new TracingBatchListListener(scope, (BatchListListener) listener);
      case "BatchSequenceListener":
        return new TracingBatchSequenceListener(scope, (BatchSequenceListener) listener);
      case "DeleteListener":
        return new TracingDeleteListener(scope, (DeleteListener) listener);
      case "ExecuteListener":
        return new TracingExecuteListener(scope, (ExecuteListener) listener);
      case "ExistsListener":
        return new TracingExistsListener(scope, (ExistsListener) listener);
      case "ExistsArrayListener":
        return new TracingExistsArrayListener(scope, (ExistsArrayListener) listener);
      case "ExistsSequenceListener":
        return new TracingExistsSequenceListener(scope, (ExistsSequenceListener) listener);
      case "RecordListener":
        return new TracingRecordListener(scope, (RecordListener) listener);
      case "RecordArrayListener":
        return new TracingRecordArrayListener(scope, (RecordArrayListener) listener);
      case "RecordSequenceListener":
        return new TracingRecordSequenceListener(scope, (RecordSequenceListener) listener);
      case "WriteListener":
        return new TracingWriteListener(scope, (WriteListener) listener);
      default:
        return listener;
    }
  }

  @Override
  public String apply(final String signature) {
    // extract the listener name from the AerospikeClient method signature
    int listenerStart = signature.indexOf(LISTENER_PKG_PREFIX);
    if (listenerStart > 0) {
      listenerStart += LISTENER_PKG_PREFIX.length();
      // the listener is never the final argument so it always ends in a comma
      final int listenerEnd = signature.indexOf(',', listenerStart);
      if (listenerEnd > 0) {
        return signature.substring(listenerStart, listenerEnd);
      }
    }
    return "";
  }
}
