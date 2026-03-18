package datadog.trace.instrumentation.hsf;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.instrumentation.hsf.HSFDecorator.DECORATE;
import static net.bytebuddy.matcher.ElementMatchers.*;

import com.google.auto.service.AutoService;
import com.taobao.hsf.invocation.Invocation;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import net.bytebuddy.asm.Advice;

/**
 * @Description @Author liurui @Date 2022/12/26 9:11
 */
@AutoService(InstrumenterModule.class)
public class HSFClientInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {
  public HSFClientInstrumentation() {
    super("hsf-client");
  }

  @Override
  public String instrumentedType() {
    //    CommonClientFilter
    return "com.taobao.hsf.common.filter.CommonClientFilter";
  }

  @Override
  public void methodAdvice(MethodTransformer transformation) {
    //    RPCFilter
    transformation.applyAdvice(
        isMethod()
            .and(isPublic())
            .and(named("invoke"))
            .and(takesArgument(1, named("com.taobao.hsf.invocation.Invocation"))),
        this.getClass().getName() + "$ClientInvokeAdvice");
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".HSFDecorator",
      packageName + ".HSFExtractAdapter",
      packageName + ".HSFInjectAdapter",
    };
  }

  //  RPCFilter
  public static class ClientInvokeAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static AgentScope before(
        @Advice.This Object filter, @Advice.Argument(1) final Invocation invocation) {
      AgentSpan span = DECORATE.buildClientSpan(invocation);
      AgentScope agentScope = activateSpan(span);
      return agentScope;
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void stopSpan(
        @Advice.Enter final AgentScope scope, @Advice.Thrown final Throwable throwable) {
      if (scope == null) {
        return;
      }
      DECORATE.onError(scope.span(), throwable);
      DECORATE.beforeFinish(scope.span());

      scope.close();
      scope.span().finish();
    }
  }
}
