package datadog.trace.instrumentation.rmi.context;

import datadog.trace.bootstrap.ContextStore;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.io.IOException;
import java.io.ObjectOutput;
import java.rmi.NoSuchObjectException;
import java.rmi.server.ObjID;
import lombok.extern.slf4j.Slf4j;
import sun.rmi.transport.Connection;
import sun.rmi.transport.StreamRemoteCall;
import sun.rmi.transport.TransportConstants;

@Slf4j
public class ContextPropagator {
  // Internal RMI object ids that we don't want to trace
  private static final ObjID ACTIVATOR_ID = new ObjID(ObjID.ACTIVATOR_ID);
  private static final ObjID DGC_ID = new ObjID(ObjID.DGC_ID);
  private static final ObjID REGISTRY_ID = new ObjID(ObjID.REGISTRY_ID);

  // RMI object id used to identify DataDog instrumentation
  public static final ObjID DD_CONTEXT_CALL_ID = new ObjID("Datadog.v1.context_call".hashCode());

  // Operation id used for checking context propagation is possible
  // RMI expects these operations to have negative identifier, as positive ones mean legacy
  // precompiled Stubs would be used instead
  private static final int CONTEXT_CHECK_CALL_OPERATION_ID = -1;
  // Seconds step of context propagation which contains actual payload
  private static final int CONTEXT_PAYLOAD_OPERATION_ID = -2;

  public static final ContextPropagator PROPAGATOR = new ContextPropagator();

  public boolean isRMIInternalObject(final ObjID id) {
    return ACTIVATOR_ID.equals(id) || DGC_ID.equals(id) || REGISTRY_ID.equals(id);
  }

  public boolean isOperationWithPayload(final int operationId) {
    return operationId == CONTEXT_PAYLOAD_OPERATION_ID;
  }

  public void attemptToPropagateContext(
      final ContextStore<Connection, Boolean> knownConnections,
      final Connection c,
      final AgentSpan span) {
    if (checkIfContextCanBePassed(knownConnections, c)) {
      if (!syntheticCall(c, ContextPayload.from(span), CONTEXT_PAYLOAD_OPERATION_ID)) {
        log.debug("Couldn't send context payload");
      }
    }
  }

  private boolean checkIfContextCanBePassed(
      final ContextStore<Connection, Boolean> knownConnections, final Connection c) {
    final Boolean storedResult = knownConnections.get(c);
    if (storedResult != null) {
      return storedResult;
    }

    final boolean result = syntheticCall(c, null, CONTEXT_CHECK_CALL_OPERATION_ID);
    knownConnections.put(c, result);
    return result;
  }

  /** @returns true when no error happened during call */
  private boolean syntheticCall(
      final Connection c, final ContextPayload payload, final int operationId) {
    final StreamRemoteCall shareContextCall = new StreamRemoteCall(c);
    try {
      c.getOutputStream().write(TransportConstants.Call);

      final ObjectOutput out = shareContextCall.getOutputStream();

      DD_CONTEXT_CALL_ID.write(out);

      // call header, part 2 (read by Dispatcher)
      out.writeInt(operationId); // in normal call this is method number (operation index)
      out.writeLong(operationId); // in normal RMI call this holds stub/skeleton hash

      // Payload should be sent only after we make sure we're connected to instrumented server
      //
      // if method is not found by un-instrumented code then writing payload will cause an exception
      // in RMI server - as the payload will be interpreted as another call
      // but it will not be parsed correctly - closing connection
      if (payload != null) {
        payload.write(out);
      }

      try {
        shareContextCall.executeCall();
      } catch (final Exception e) {
        final Exception ex = shareContextCall.getServerException();
        if (ex != null) {
          if (ex instanceof NoSuchObjectException) {
            return false;
          } else {
            log.debug("Server error when executing synthetic call", ex);
          }
        } else {
          log.debug("Error executing synthetic call", e);
        }
        return false;
      } finally {
        shareContextCall.done();
      }

    } catch (final IOException e) {
      log.debug("Communication error executing synthetic call", e);
      return false;
    }
    return true;
  }
}
