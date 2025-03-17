package datadog.trace.instrumentation.hsf;

import static datadog.context.propagation.Propagators.defaultPropagator;
import static datadog.trace.bootstrap.instrumentation.api.AgentPropagation.extractContextAndGetSpanContext;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.instrumentation.hsf.HSFExtractAdapter.GETTER;
import static datadog.trace.instrumentation.hsf.HSFInjectAdapter.SETTER;

import com.taobao.hsf.context.RPCContext;
import com.taobao.hsf.invocation.Invocation;
import com.taobao.hsf.util.PojoUtils;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentSpanContext;
import datadog.trace.bootstrap.instrumentation.decorator.BaseDecorator;
import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * @Description
 * @Author liurui
 * @Date 2022/12/26 10:18
 */
public class HSFDecorator extends BaseDecorator {
  public static final HSFDecorator DECORATE = new HSFDecorator();
  @Override
  protected String[] instrumentationNames() {
    return new String[]{"taobao-hsf","HSF"};
  }

  @Override
  protected CharSequence spanType() {
    return "hsf";
  }

  @Override
  protected CharSequence component() {
    return "taobao-hsf";
  }

  public AgentSpan buildClientSpan(Invocation invocation){
    Invocation.ClientInvocationContext context = invocation.getClientInvocationContext();
    String methodInterface = context.getMethodModel().getUniqueName();
    String methodName = context.getMethodModel().getMethodName();
    AgentSpan span = startSpan(component());
    span.setResourceName(methodInterface +":"+ methodName);
    span.setTag("invoke_type",context.getMethodModel().getInvokeType().toLowerCase());
    afterStart(span);
    span.setTag("args",methodArgs(invocation));
    span.setTag("argsV",methodArgsV(invocation));
    defaultPropagator().inject(span, RPCContext.getClientContext(), SETTER);
    return span;
  }

  public AgentSpan buildServerSpan(Invocation invocation){
    AgentSpanContext parentContext = extractContextAndGetSpanContext(RPCContext.getServerContext(), GETTER);
    AgentSpan span = startSpan(component(),parentContext);

    span.setResourceName(invocation.getServerInvocationContext().getMetadata().getUniqueName());
    afterStart(span);
    return span;
  }

  private String methodArgs(Invocation invocation){
    String[]  sigs = invocation.getMethodArgSigs();
    return Arrays.stream(sigs).collect(Collectors.joining(","));
  }
  private String methodArgsV(Invocation invocation){
    Object [] args = invocation.getMethodArgs();
    return Arrays.stream(args).map(arg->PojoUtils.generalize(arg).toString()).collect(Collectors.joining(","));
  }
}
