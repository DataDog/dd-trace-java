package datadog.trace.instrumentation.sapjco;

import com.sap.conn.jco.ext.ServerPassportManager;
import com.sap.jdsr.writer.DsrIPassport;

/**
 * Captures the SAP passport of an inbound RFC request so the server span opened around {@code
 * JCoServerFunctionHandler.handleRequest} can be correlated with the trace that issued it. JCo
 * calls {@link #requestStarted} on the worker thread just before dispatching to the handler, so the
 * decoded ids are handed to the advice through a {@link ThreadLocal}.
 */
public final class DatadogServerPassportManager implements ServerPassportManager {

  @Override
  public void requestStarted(int requestId, DsrIPassport passport, String function) {
    InboundPassport.set(InboundPassport.from(passport));
  }

  @Override
  public void requestFinished(int requestId, long bytesSent, long bytesReceived) {
    // The span lifecycle is driven by the handleRequest advice; drop any uncollected context.
    InboundPassport.getAndClear();
  }
}
