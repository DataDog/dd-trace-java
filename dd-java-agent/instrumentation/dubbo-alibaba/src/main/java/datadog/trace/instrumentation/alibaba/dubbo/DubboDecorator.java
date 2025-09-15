package datadog.trace.instrumentation.alibaba.dubbo;

import static datadog.context.propagation.Propagators.defaultPropagator;
import static datadog.trace.bootstrap.instrumentation.api.AgentPropagation.extractContextAndGetSpanContext;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.*;
import static datadog.trace.instrumentation.alibaba.dubbo.DubboConstants.*;
import static datadog.trace.instrumentation.alibaba.dubbo.DubboHeadersExtractAdapter.GETTER;
import static datadog.trace.instrumentation.alibaba.dubbo.DubboHeadersInjectAdapter.SETTER;

import com.alibaba.dubbo.common.URL;
import com.alibaba.dubbo.common.utils.StringUtils;
import com.alibaba.dubbo.rpc.Invocation;
import com.alibaba.dubbo.rpc.Invoker;
import com.alibaba.dubbo.rpc.RpcContext;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentSpanContext;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.bootstrap.instrumentation.decorator.BaseDecorator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DubboDecorator extends BaseDecorator {
  private static final Logger log = LoggerFactory.getLogger(DubboDecorator.class);
  public static final CharSequence DUBBO_REQUEST = UTF8BytesString.create("dubbo");

  public static final CharSequence DUBBO_SERVER = UTF8BytesString.create("alibaba-dubbo");

  public static final DubboDecorator DECORATE = new DubboDecorator();

  @Override
  protected String[] instrumentationNames() {
    return new String[]{"apache-dubbo"};
  }

  @Override
  protected CharSequence spanType() {
    return DUBBO_SERVER;
  }

  @Override
  protected CharSequence component() {
    return DUBBO_SERVER;
  }

  public AgentSpan startDubboSpan(Invoker invoker, Invocation invocation) {
    URL url = invoker.getUrl();
    boolean isConsumer = isConsumerSide(url);

    String methodName = invocation.getMethodName();
    String resourceName = generateOperationName(url,invocation);
    String shortUrl = generateRequestURL(url,invocation);
    if (log.isDebugEnabled()) {
      log.debug("isConsumer:{},method:{},resourceName:{},shortUrl:{},longUrl:{},version:{}",
          isConsumer,
          methodName,
          resourceName,
          shortUrl,
          url.toString(),
          getVersion(url)
      );
    }
    AgentSpan span;
    RpcContext rpcContext = RpcContext.getContext();
    if (isConsumer){
      // this is consumer
      span = startSpan(DUBBO_REQUEST);
    }else{
      // this is provider
      AgentSpanContext parentContext = extractContextAndGetSpanContext(rpcContext, GETTER);
      span = startSpan(DUBBO_REQUEST,parentContext);
    }
    span.setTag(TAG_URL, url.toString());
    span.setTag(TAG_SHORT_URL, shortUrl);
    span.setTag(TAG_METHOD, methodName);
    span.setTag(TAG_VERSION,getVersion(url));
    span.setTag(TAG_SIDE,isConsumer?CONSUMER_SIDE:PROVIDER_SIDE);
    afterStart(span);

    withMethod(span, resourceName);
    //propagate().inject(span, rpcContext, SETTER);
    defaultPropagator().inject(span, rpcContext, SETTER);

    return span;
  }

  public void withMethod(final AgentSpan span, final String methodName) {
    span.setResourceName(methodName);
  }

  @Override
  public AgentSpan afterStart(AgentSpan span) {
    return super.afterStart(span);
  }


  private String generateOperationName(URL requestURL, Invocation invocation) {
    StringBuilder operationName = new StringBuilder();
    String groupStr = requestURL.getParameter(GROUP_KEY);
    groupStr = StringUtils.isEmpty(groupStr) ? "" : groupStr + "/";
    operationName.append(groupStr);
    operationName.append(requestURL.getPath());
    operationName.append("." + invocation.getMethodName() + "(");
    for (Class<?> classes : invocation.getParameterTypes()) {
      operationName.append(classes.getSimpleName() + ",");
    }
    if (invocation.getParameterTypes().length > 0) {
      operationName.delete(operationName.length() - 1, operationName.length());
    }
    operationName.append(")");
    return operationName.toString();
  }

  private String generateRequestURL(URL url, Invocation invocation) {
    StringBuilder requestURL = new StringBuilder();
    requestURL.append(url.getProtocol() + "://");
    requestURL.append(url.getHost());
    requestURL.append(":" + url.getPort() + "/");
    requestURL.append(generateOperationName(url, invocation));
    return requestURL.toString();
  }

  public boolean isConsumerSide(URL url) {
    return url.getParameter(SIDE_KEY, PROVIDER_SIDE).equals(CONSUMER_SIDE);
  }

  public AgentScope buildSpan(Invoker invoker, Invocation invocation) {
    AgentSpan span = startDubboSpan(invoker,invocation);
    AgentScope agentScope = activateSpan(span);
    return agentScope;
  }

  private String getVersion(URL url){
    return url.getParameter(VERSION);
  }
}
