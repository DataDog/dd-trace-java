package datadog.trace.instrumentation.sapjco;

import com.sap.conn.jco.AbapClassException;
import com.sap.conn.jco.AbapException;
import com.sap.conn.jco.JCoDestination;
import com.sap.conn.jco.JCoException;
import com.sap.conn.jco.JCoFunction;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.InternalSpanTypes;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.bootstrap.instrumentation.decorator.ClientDecorator;

/**
 * Decorates the client span opened around an outbound SAP RFC call ({@code JCoFunction.execute}).
 */
public class JCoClientDecorator extends ClientDecorator {

  public static final CharSequence OPERATION_NAME = UTF8BytesString.create("sap.rfc.call");
  public static final CharSequence METADATA_OPERATION = UTF8BytesString.create("sap.rfc.metadata");
  private static final CharSequence COMPONENT_NAME = UTF8BytesString.create("sap-jco");

  /** OTel-style RPC system identifier for SAP Remote Function Calls. */
  private static final String RPC_SYSTEM_SAP_RFC = "sap_rfc";

  /** The RFC JCo issues to fetch a function's interface before calling it. */
  private static final String METADATA_RFM = "RFC_METADATA_GET";

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
    return null; // keep the caller's service name
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
    setIfPresent(span, "sap.destination", destination.getDestinationName());
    setIfPresent(span, "sap.system.number", destination.getSystemNumber());
    setIfPresent(span, "sap.client", destination.getClient());
    setIfPresent(span, "sap.user", destination.getUser());
    setIfPresent(span, "sap.host", destination.getApplicationServerHost());
  }

  /**
   * Tags a metadata-lookup span (RFC_METADATA_GET) with the function whose interface is fetched.
   */
  public void onMetadata(AgentSpan span, String function) {
    span.setTag("rpc.system", RPC_SYSTEM_SAP_RFC);
    span.setTag("rpc.method", METADATA_RFM);
    // Resource reads distinctly from the sap.rfc.call span for the same function (just the function
    // name): here it is the function qualified as a metadata lookup.
    span.setResourceName(function == null ? METADATA_RFM : function + " (metadata)");
  }

  /** Marks a metadata span as an error when SAP has no such RFC function. */
  public void onFunctionNotFound(AgentSpan span, String function) {
    span.setError(true);
    span.setTag("error.type", "function_not_found");
    span.setTag("error.message", "SAP has no RFC function " + function);
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
