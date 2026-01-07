package datadog.trace.instrumentation.cics;

import com.ibm.connector2.cics.ECIInteractionSpec;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.InternalSpanTypes;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.bootstrap.instrumentation.decorator.ClientDecorator;
import java.net.InetAddress;
import java.net.InetSocketAddress;

public class CicsDecorator extends ClientDecorator {
  public static final CharSequence CICS_CLIENT = UTF8BytesString.create("cics-client");
  public static final CharSequence ECI_EXECUTE_OPERATION = UTF8BytesString.create("cics.execute");
  public static final CharSequence GATEWAY_FLOW_OPERATION = UTF8BytesString.create("gateway.flow");

  public static final CicsDecorator DECORATE = new CicsDecorator();

  @Override
  protected String[] instrumentationNames() {
    return new String[] {"cics"};
  }

  @Override
  protected String service() {
    return null; // Use default service name
  }

  @Override
  protected CharSequence component() {
    return CICS_CLIENT;
  }

  @Override
  protected CharSequence spanType() {
    return InternalSpanTypes.RPC;
  }

  @Override
  public AgentSpan afterStart(AgentSpan span) {
    assert span != null;
    span.setTag("rpc.system", "cics");
    return super.afterStart(span);
  }

  /**
   * Adds connection details to a span from JavaGatewayInterface fields.
   *
   * @param span the span to decorate
   * @param strAddress the hostname/address string
   * @param port the port number
   * @param ipGateway the resolved InetAddress (can be null)
   */
  public AgentSpan onConnection(
      final AgentSpan span, final String strAddress, final int port, final InetAddress ipGateway) {
    if (strAddress != null) {
      span.setTag(Tags.PEER_HOSTNAME, strAddress);
    }

    if (ipGateway != null) {
      onPeerConnection(span, ipGateway, false);
    }

    if (port > 0) {
      setPeerPort(span, port);
    }

    return span;
  }

  /**
   * Adds local connection details to a span from a socket address.
   *
   * @param span the span to decorate
   * @param localAddr the socket (can be null)
   */
  public AgentSpan onLocalConnection(final AgentSpan span, final InetSocketAddress localAddr) {
    if (localAddr != null && localAddr.getAddress() != null) {
      span.setTag("network.local.address", localAddr.getAddress().getHostAddress());
      span.setTag("network.local.port", localAddr.getPort());
    }
    return span;
  }

  /**
   * Converts ECI interaction verb code to string representation.
   *
   * @param verb the interaction verb code
   * @return string representation of the verb
   * @see <a
   *     href="https://docs.oracle.com/javaee/6/api/constant-values.html#javax.resource.cci.InteractionSpec.SYNC_SEND">InteractionSpec
   *     constants</a>
   */
  private String getInteractionVerbString(final int verb) {
    switch (verb) {
      case 0:
        return "SYNC_SEND";
      case 1:
        return "SYNC_SEND_RECEIVE";
      case 2:
        return "SYNC_RECEIVE";
      default:
        return "UNKNOWN_" + verb;
    }
  }

  public AgentSpan onECIInteraction(final AgentSpan span, final ECIInteractionSpec spec) {
    final String interactionVerb = getInteractionVerbString(spec.getInteractionVerb());
    final String functionName = spec.getFunctionName();
    final String tranName = spec.getTranName();
    final String tpnName = spec.getTPNName();

    span.setResourceName(interactionVerb + " " + functionName);
    span.setTag("cics.interaction", interactionVerb);

    if (functionName != null) {
      span.setTag("rpc.method", functionName);
    }
    if (tranName != null) {
      span.setTag("cics.tran", tranName);
    }
    if (tpnName != null) {
      span.setTag("cics.tpn", tpnName);
    }

    return span;
  }
}
