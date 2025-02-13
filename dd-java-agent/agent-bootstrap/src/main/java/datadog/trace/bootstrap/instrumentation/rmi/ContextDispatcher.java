package datadog.trace.bootstrap.instrumentation.rmi;

import static datadog.trace.bootstrap.instrumentation.api.AgentPropagation.extractContextAndGetSpanContext;
import static datadog.trace.bootstrap.instrumentation.rmi.ContextPropagator.DD_CONTEXT_CALL_ID;
import static datadog.trace.bootstrap.instrumentation.rmi.ContextPropagator.PROPAGATOR;
import static datadog.trace.bootstrap.instrumentation.rmi.ThreadLocalContext.THREAD_LOCAL_CONTEXT;

import datadog.trace.bootstrap.instrumentation.api.AgentSpanContext;
import datadog.trace.bootstrap.instrumentation.api.ContextVisitors;
import java.io.IOException;
import java.io.ObjectInput;
import java.rmi.Remote;
import java.rmi.server.RemoteCall;
import sun.rmi.server.Dispatcher;
import sun.rmi.transport.Target;

/**
 * ContextDispatcher is responsible for handling both initial context propagation check call and
 * following call which carries payload
 *
 * <p>Context propagation check is only expected not to throw any exception, hinting to the client
 * that its communicating with an instrumented server. Non instrumented server would've thrown
 * UnknownObjectException
 *
 * <p>Because caching of the result after first call on a connection, only payload calls are
 * expected
 */
public class ContextDispatcher implements Dispatcher {
  private static final ContextDispatcher CONTEXT_DISPATCHER = new ContextDispatcher();
  private static final NoopRemote NOOP_REMOTE = new NoopRemote();

  public static Target newDispatcherTarget() {
    return new Target(NOOP_REMOTE, CONTEXT_DISPATCHER, NOOP_REMOTE, DD_CONTEXT_CALL_ID, false);
  }

  @Override
  public void dispatch(final Remote obj, final RemoteCall call) throws IOException {
    final ObjectInput in = call.getInputStream();
    final int operationId = in.readInt();
    in.readLong(); // skip 8 bytes

    if (PROPAGATOR.isOperationWithPayload(operationId)) {
      final ContextPayload payload = ContextPayload.read(in);
      if (payload != null) {
        final AgentSpanContext context =
            extractContextAndGetSpanContext(
                payload.getContext(), ContextVisitors.stringValuesMap());
        THREAD_LOCAL_CONTEXT.set(context);
      }
    }

    // send result stream the client is expecting
    call.getResultStream(true);

    // release held streams to allow next call to continue
    call.releaseInputStream();
    call.releaseOutputStream();
    call.done();
  }

  public static class NoopRemote implements Remote {}
}
