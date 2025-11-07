package datadog.trace.instrumentation.aerospike4;

import static datadog.trace.instrumentation.aerospike4.AerospikeClientDecorator.DECORATE;

import com.aerospike.client.AerospikeException;
import com.aerospike.client.BatchRead;
import com.aerospike.client.Key;
import com.aerospike.client.Record;
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
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentScope.Continuation;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.util.List;

public final class TracingListener
    implements ExistsListener,
        ExistsSequenceListener,
        ExistsArrayListener,
        RecordListener,
        RecordSequenceListener,
        RecordArrayListener,
        BatchSequenceListener,
        BatchListListener,
        WriteListener,
        ExecuteListener,
        DeleteListener {

  protected final AgentSpan clientSpan;
  protected final Continuation continuation;
  protected final Object listener;

  public TracingListener(
      final AgentSpan clientSpan, final Continuation continuation, final Object listener) {
    this.clientSpan = clientSpan;
    this.continuation = continuation;
    this.listener = listener;
  }

  @Override
  public void onExists(final Key key, final boolean exists) {
    if (listener != null) {
      ((ExistsSequenceListener) listener).onExists(key, exists);
    }
  }

  @Override
  public void onRecord(final Key key, final Record record) throws AerospikeException {
    if (listener != null) {
      ((RecordSequenceListener) listener).onRecord(key, record);
    }
  }

  @Override
  public void onRecord(final BatchRead record) {
    if (listener != null) {
      ((BatchSequenceListener) listener).onRecord(record);
    }
  }

  @Override
  public void onSuccess(final Key key, final boolean exists) {
    DECORATE.beforeFinish(clientSpan);
    clientSpan.finish();

    if (listener != null) {
      try (final AgentScope scope = continuation.activate()) {
        if (listener instanceof ExistsListener) {
          ((ExistsListener) listener).onSuccess(key, exists);
        } else if (listener instanceof DeleteListener) {
          ((DeleteListener) listener).onSuccess(key, exists);
        }
      }
    } else {
      continuation.cancel();
    }
  }

  @Override
  public void onSuccess(final Key[] keys, final boolean[] exists) {
    DECORATE.beforeFinish(clientSpan);
    clientSpan.finish();

    if (listener != null) {
      try (final AgentScope scope = continuation.activate()) {
        ((ExistsArrayListener) listener).onSuccess(keys, exists);
      }
    } else {
      continuation.cancel();
    }
  }

  @Override
  public void onSuccess(final Key key, final Record record) {
    DECORATE.beforeFinish(clientSpan);
    clientSpan.finish();

    if (listener != null) {
      try (final AgentScope scope = continuation.activate()) {
        ((RecordListener) listener).onSuccess(key, record);
      }
    } else {
      continuation.cancel();
    }
  }

  @Override
  public void onSuccess(final Key[] keys, final Record[] records) {
    DECORATE.beforeFinish(clientSpan);
    clientSpan.finish();

    if (listener != null) {
      try (final AgentScope scope = continuation.activate()) {
        ((RecordArrayListener) listener).onSuccess(keys, records);
      }
    } else {
      continuation.cancel();
    }
  }

  @Override
  public void onSuccess(final List<BatchRead> records) {
    DECORATE.beforeFinish(clientSpan);
    clientSpan.finish();

    if (listener != null) {
      try (final AgentScope scope = continuation.activate()) {
        ((BatchListListener) listener).onSuccess(records);
      }
    } else {
      continuation.cancel();
    }
  }

  @Override
  public void onSuccess(final Key key) {
    DECORATE.beforeFinish(clientSpan);
    clientSpan.finish();

    if (listener != null) {
      try (final AgentScope scope = continuation.activate()) {
        ((WriteListener) listener).onSuccess(key);
      }
    } else {
      continuation.cancel();
    }
  }

  @Override
  public void onSuccess(final Key key, final Object obj) {
    DECORATE.beforeFinish(clientSpan);
    clientSpan.finish();

    if (listener != null) {
      try (final AgentScope scope = continuation.activate()) {
        ((ExecuteListener) listener).onSuccess(key, obj);
      }
    } else {
      continuation.cancel();
    }
  }

  @Override
  public void onSuccess() {
    DECORATE.beforeFinish(clientSpan);
    clientSpan.finish();

    if (listener != null) {
      try (final AgentScope scope = continuation.activate()) {
        if (listener instanceof ExistsSequenceListener) {
          ((ExistsSequenceListener) listener).onSuccess();
        } else if (listener instanceof RecordSequenceListener) {
          ((RecordSequenceListener) listener).onSuccess();
        } else if (listener instanceof BatchSequenceListener) {
          ((BatchSequenceListener) listener).onSuccess();
        }
      }
    } else {
      continuation.cancel();
    }
  }

  @Override
  public void onFailure(final AerospikeException error) {
    DECORATE.onError(clientSpan, error);
    DECORATE.beforeFinish(clientSpan);
    clientSpan.finish();

    if (listener != null) {
      try (final AgentScope scope = continuation.activate()) {
        if (listener instanceof ExistsListener) {
          ((ExistsListener) listener).onFailure(error);
        } else if (listener instanceof ExistsSequenceListener) {
          ((ExistsSequenceListener) listener).onFailure(error);
        } else if (listener instanceof ExistsArrayListener) {
          ((ExistsArrayListener) listener).onFailure(error);
        } else if (listener instanceof RecordListener) {
          ((RecordListener) listener).onFailure(error);
        } else if (listener instanceof RecordSequenceListener) {
          ((RecordSequenceListener) listener).onFailure(error);
        } else if (listener instanceof RecordArrayListener) {
          ((RecordArrayListener) listener).onFailure(error);
        } else if (listener instanceof BatchSequenceListener) {
          ((BatchSequenceListener) listener).onFailure(error);
        } else if (listener instanceof BatchListListener) {
          ((BatchListListener) listener).onFailure(error);
        } else if (listener instanceof WriteListener) {
          ((WriteListener) listener).onFailure(error);
        } else if (listener instanceof ExecuteListener) {
          ((ExecuteListener) listener).onFailure(error);
        } else if (listener instanceof DeleteListener) {
          ((DeleteListener) listener).onFailure(error);
        }
      }
    } else {
      continuation.cancel();
    }
  }
}
