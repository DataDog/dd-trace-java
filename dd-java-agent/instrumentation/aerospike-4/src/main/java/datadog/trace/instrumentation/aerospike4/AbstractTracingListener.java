package datadog.trace.instrumentation.aerospike4;

import static datadog.trace.instrumentation.aerospike4.AerospikeClientDecorator.DECORATE;

import com.aerospike.client.AerospikeException;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.context.TraceScope.Continuation;

public class AbstractTracingListener<L> {
  protected final AgentSpan clientSpan;
  protected final Continuation continuation;
  protected final L listener;

  public AbstractTracingListener(final AgentScope clientScope, final L listener) {
    this.clientSpan = clientScope.span();
    this.continuation = clientScope.capture();
    this.listener = listener;
  }

  public void onSuccess() {
    DECORATE.beforeFinish(clientSpan);
    clientSpan.finish();
  }

  public void onFailure(final AerospikeException cause) {
    DECORATE.onError(clientSpan, cause);
    DECORATE.beforeFinish(clientSpan);
    clientSpan.finish();
  }
}
