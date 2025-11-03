package datadog.trace.instrumentation.dubbo_3_2;


import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentSpanContext;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.bootstrap.instrumentation.decorator.BaseDecorator;
import org.apache.dubbo.rpc.RpcInvocation;
import org.apache.dubbo.rpc.TriRpcStatus;
import org.apache.dubbo.rpc.protocol.tri.RequestMetadata;
import org.apache.dubbo.rpc.protocol.tri.call.TripleClientCall;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static datadog.trace.bootstrap.instrumentation.api.AgentPropagation.extractContextAndGetSpanContext;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.*;
import static datadog.trace.instrumentation.dubbo_3_2.DubboHeadersExtractAdapter.GETTER;

public class DubboDecorator extends BaseDecorator {
  private static final Logger log = LoggerFactory.getLogger(DubboDecorator.class);
  public static final CharSequence DUBBO_REQUEST = UTF8BytesString.create("dubbo");

  public static final CharSequence DUBBO_SERVER = UTF8BytesString.create("apache-dubbo");

  public static final DubboDecorator DECORATE = new DubboDecorator();

  @Override
  protected String[] instrumentationNames() {
    return new String[] {"apache-dubbo"};
  }

  @Override
  protected CharSequence spanType() {
    return DUBBO_SERVER;
  }

  @Override
  protected CharSequence component() {
    return DUBBO_SERVER;
  }

  public void withMethod(final AgentSpan span, final String methodName) {
    span.setResourceName(methodName);
  }

  public AgentScope obToClientCall(TripleClientCall tripleClientCall){

    try {
      RequestMetadata requestMetadata = (RequestMetadata)Dubbo3Constants.getValue(TripleClientCall.class, tripleClientCall, "requestMetadata");
      DubboMetadata metadata = new DubboMetadata(null,requestMetadata);
      AgentSpanContext parentContext = extractContextAndGetSpanContext(metadata, GETTER);
      AgentSpan span = startSpan("onMessage", parentContext);
//      defaultPropagator().inject(span, metadata, SETTER);
      return  activateSpan(span);
    }catch (Exception e){
      e.printStackTrace();
    }
    return activateSpan(activeSpan());
  }

  public AgentScope obToClientCallClose(TripleClientCall tripleClientCall, TriRpcStatus status) {
    try {
      RequestMetadata requestMetadata = (RequestMetadata)Dubbo3Constants.getValue(TripleClientCall.class, tripleClientCall, "requestMetadata");
      DubboMetadata metadata = new DubboMetadata(null,requestMetadata);
      AgentSpanContext parentContext = extractContextAndGetSpanContext(metadata, GETTER);
      AgentSpan span = startSpan(status.isOk() ? "onCompleted" : "onError", parentContext);
      return  activateSpan(span);
    }catch (Exception e){
      e.printStackTrace();
    }
    return activateSpan(activeSpan());
  }

  public AgentScope serverCall(RpcInvocation rpcInvocation){

    try {
      DubboMetadata metadata = new DubboMetadata(rpcInvocation,null);
      AgentSpanContext parentContext = extractContextAndGetSpanContext(metadata, GETTER);
      AgentSpan span = startSpan("onMessage", parentContext);
//      defaultPropagator().inject(span, metadata, SETTER);
      return  activateSpan(span);
    }catch (Exception e){
      e.printStackTrace();
    }
    return activateSpan(activeSpan());
  }

  public AgentScope serverCall(RpcInvocation rpcInvocation, Long status) {
    try {
      DubboMetadata metadata = new DubboMetadata(rpcInvocation,null);
      AgentSpanContext parentContext = extractContextAndGetSpanContext(metadata, GETTER);
      AgentSpan span = startSpan("onCancel", parentContext);
//      defaultPropagator().inject(span, metadata, SETTER);
      return  activateSpan(span);
    }catch (Exception e){
      e.printStackTrace();
    }
    return activateSpan(activeSpan());
  }

  public AgentScope serverCallOnComplete(RpcInvocation invocation) {
    try {
      DubboMetadata metadata = new DubboMetadata(invocation,null);
      AgentSpanContext parentContext = extractContextAndGetSpanContext(metadata, GETTER);
      AgentSpan span = startSpan("onCompleted", parentContext);
      return  activateSpan(span);
    }catch (Exception e){
      e.printStackTrace();
    }
    return activateSpan(activeSpan());
  }
}
