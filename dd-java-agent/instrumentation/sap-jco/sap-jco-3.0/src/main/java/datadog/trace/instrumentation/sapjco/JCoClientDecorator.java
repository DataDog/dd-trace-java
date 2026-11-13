package datadog.trace.instrumentation.sapjco;

import com.sap.conn.jco.AbapClassException;
import com.sap.conn.jco.AbapException;
import com.sap.conn.jco.JCoDestination;
import com.sap.conn.jco.JCoException;
import com.sap.conn.jco.JCoFunction;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.InternalSpanTypes;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.bootstrap.instrumentation.decorator.ClientDecorator;

/**
 * Decorates the client span opened around an outbound SAP RFC call ({@code JCoFunction.execute}).
 */
public class JCoClientDecorator extends ClientDecorator {

  public static final CharSequence OPERATION_NAME = UTF8BytesString.create("sap.rfc.call");
  private static final CharSequence COMPONENT_NAME = UTF8BytesString.create("sap-jco");

  /** OTel-style RPC system identifier for SAP Remote Function Calls. */
  private static final String RPC_SYSTEM_SAP_RFC = "sap_rfc";

  public static final JCoClientDecorator DECORATE = new JCoClientDecorator();

  @Override
  protected String[] instrumentationNames() {
    return new String[] {"sap-jco"};
  }

  @Override
  protected CharSequence component() {
    return COMPONENT_NAME;
  }

  @Override
  protected CharSequence spanType() {
    return InternalSpanTypes.RPC;
  }

  @Override
  protected String service() {
    return null; // keep the caller's service; peer.service identifies the SAP system
  }

  /** Tags the span with the RFC function being called; the function name is the span's resource. */
  public void onFunction(AgentSpan span, JCoFunction function) {
    span.setTag("rpc.system", RPC_SYSTEM_SAP_RFC);
    if (function == null) {
      return;
    }
    String name = function.getName();
    span.setTag("rpc.method", name);
    if (name != null) {
      span.setResourceName(name);
    }
  }

  /**
   * Tags the span with the SAP destination/system the call targets. Uses only cheap local getters.
   */
  public void onConnection(AgentSpan span, JCoDestination destination) {
    if (destination == null) {
      return;
    }
    String systemId = destination.getR3Name();
    String peerService =
        systemId != null && !systemId.isEmpty() ? systemId : destination.getDestinationName();
    setIfPresent(span, Tags.RPC_SERVICE, peerService);
    setIfPresent(span, Tags.PEER_SERVICE, peerService);
    setIfPresent(span, "sap.destination", destination.getDestinationName());
    setIfPresent(span, "sap.system.id", systemId);
    setIfPresent(span, "sap.system.number", destination.getSystemNumber());
    setIfPresent(span, "sap.client", destination.getClient());
    setIfPresent(span, "sap.user", destination.getUser());
    String host = destination.getApplicationServerHost();
    if (host != null) {
      span.setTag(Tags.PEER_HOSTNAME, host);
    }
  }

  /** Flags business (ABAP) and transport (JCo) failures on the span. */
  public void onRfcError(AgentSpan span, Throwable throwable) {
    if (throwable == null) {
      return;
    }
    span.setError(true);
    if (throwable instanceof AbapException || throwable instanceof AbapClassException) {
      // A business exception raised by the ABAP function itself.
      span.setTag("error.type", ((JCoException) throwable).getKey());
    }
    onError(span, throwable);
  }

  private static void setIfPresent(AgentSpan span, String key, String value) {
    if (value != null && !value.isEmpty()) {
      span.setTag(key, value);
    }
  }
}
