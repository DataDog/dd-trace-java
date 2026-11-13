package datadog.trace.instrumentation.sapjco;

import com.sap.conn.jco.AbapClassException;
import com.sap.conn.jco.AbapException;
import com.sap.conn.jco.JCoAttributes;
import com.sap.conn.jco.JCoException;
import com.sap.conn.jco.JCoFunction;
import com.sap.conn.jco.server.JCoServerCallType;
import com.sap.conn.jco.server.JCoServerContext;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.InternalSpanTypes;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.bootstrap.instrumentation.decorator.ServerDecorator;

/** Decorates the server span opened around an inbound SAP RFC call ({@code handleRequest}). */
public class JCoServerDecorator extends ServerDecorator {

  public static final CharSequence OPERATION_NAME = UTF8BytesString.create("sap.rfc.request");
  private static final CharSequence COMPONENT_NAME = UTF8BytesString.create("sap-jco");

  public static final JCoServerDecorator DECORATE = new JCoServerDecorator();

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

  public void onRequest(
      AgentSpan span, JCoServerContext serverCtx, JCoFunction function, InboundPassport inbound) {
    span.setTag("rpc.system", "sap_rfc");
    if (function != null) {
      String name = function.getName();
      span.setTag("rpc.method", name);
      if (name != null) {
        span.setResourceName(name);
      }
    }
    if (serverCtx != null) {
      setIfPresent(span, "sap.tid", serverCtx.getTID());
      JCoServerCallType callType = serverCtx.getCallType();
      if (callType != null) {
        span.setTag("sap.call.type", callType.name());
      }
      JCoAttributes attributes = serverCtx.getConnectionAttributes();
      if (attributes != null) {
        setIfPresent(span, "sap.system.id", attributes.getSystemID());
        setIfPresent(span, "sap.client", attributes.getClient());
        setIfPresent(span, "sap.user", attributes.getUser());
        String host = attributes.getPartnerHost();
        if (host != null) {
          span.setTag(Tags.PEER_HOSTNAME, host);
        }
      }
    }
    if (inbound != null) {
      // Correlation only for now; full remote-parent continuation is a follow-up.
      span.setTag("sap.epp.trace_id", inbound.traceIdHex);
      span.setTag("sap.epp.parent_id", inbound.parentSpanIdHex);
    }
  }

  /** Flags business (ABAP) and transport (JCo) failures on the span. */
  public void onRfcError(AgentSpan span, Throwable throwable) {
    if (throwable == null) {
      return;
    }
    span.setError(true);
    if (throwable instanceof AbapException || throwable instanceof AbapClassException) {
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
