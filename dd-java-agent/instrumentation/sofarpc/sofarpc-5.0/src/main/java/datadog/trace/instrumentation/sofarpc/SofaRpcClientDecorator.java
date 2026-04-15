package datadog.trace.instrumentation.sofarpc;

import com.alipay.sofa.rpc.core.request.SofaRequest;
import com.alipay.sofa.rpc.core.response.SofaResponse;
import datadog.trace.api.naming.SpanNaming;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.InternalSpanTypes;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.bootstrap.instrumentation.decorator.ClientDecorator;

public class SofaRpcClientDecorator extends ClientDecorator {

  public static final CharSequence SOFA_RPC_CLIENT =
      UTF8BytesString.create(
          SpanNaming.instance().namingSchema().client().operationForProtocol("sofarpc"));

  private static final CharSequence COMPONENT_NAME = UTF8BytesString.create("sofarpc-client");

  public static final SofaRpcClientDecorator DECORATE = new SofaRpcClientDecorator();

  @Override
  protected String[] instrumentationNames() {
    return new String[] {"sofarpc"};
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
    return null;
  }

  public AgentSpan onRequest(AgentSpan span, SofaRequest request) {
    span.setTag("rpc.system", "sofarpc");
    if (request == null) {
      return span;
    }
    String serviceName = request.getTargetServiceUniqueName();
    String methodName = request.getMethodName();
    span.setTag(Tags.RPC_SERVICE, serviceName);
    // peer.service is derived automatically by PeerServiceCalculator from rpc.service.
    if (serviceName != null && methodName != null) {
      span.setResourceName(serviceName + "/" + methodName);
    } else if (methodName != null) {
      span.setResourceName(methodName);
    }
    return span;
  }

  public AgentSpan onResponse(AgentSpan span, SofaResponse response) {
    if (response != null && response.isError()) {
      span.setError(true);
      span.setTag("error.message", response.getErrorMsg());
    }
    return span;
  }
}
