package datadog.trace.instrumentation.dubbo_3_2;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import org.apache.dubbo.rpc.RpcInvocation;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.extendsClass;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.nameStartsWith;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.instrumentation.dubbo_3_2.DubboDecorator.DECORATE;
import static net.bytebuddy.matcher.ElementMatchers.*;

@AutoService(InstrumenterModule.class)
public class DubboServerCallInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForTypeHierarchy, Instrumenter.HasMethodAdvice {


  public DubboServerCallInstrumentation() {
    super("apache-dubbo","dubbo-client-call");
  }

  @Override
  public String hierarchyMarkerType() {
    return null;
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return extendsClass(
        named("org.apache.dubbo.rpc.protocol.tri.call.AbstractServerCallListener")
            .or(named("org.apache.dubbo.rpc.protocol.tri.h12.AbstractServerCallListener")));
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
        packageName + ".DubboServerCallAdvice",
        packageName + ".DubboDecorator",
        packageName + ".DubboHeadersExtractAdapter",
        packageName + ".Dubbo3Constants",
        packageName + ".DubboMetadata",
        packageName + ".DubboHeadersInjectAdapter",
    };
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod()
            .and(isPublic())
            .and(nameStartsWith("onMessage")),
        packageName + ".DubboServerCallAdvice");
    transformer.applyAdvice(
        isMethod().and(nameStartsWith("onCancel")).and(takesArgument(0, long.class)),
        DubboServerCallInstrumentation.class.getName() + "$CancelAdvice");
    transformer.applyAdvice(
        isMethod().and(nameStartsWith("onComplete")),
        DubboServerCallInstrumentation.class.getName() + "$CompleteAdvice");
  }

  public static class CancelAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static AgentScope onEnter(@Advice.Argument(0) long status, @Advice.FieldValue("invocation") RpcInvocation invocation) {
      return DECORATE.serverCall(invocation,status);
    }
    //
    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void stopSpan(
        @Advice.Enter final AgentScope scope, @Advice.Thrown final Throwable throwable) {
      if (scope == null) {
        return;
      }
      DECORATE.beforeFinish(scope.span());
      scope.close();
      scope.span().finish();
    }

  }

  public static class CompleteAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static AgentScope onEnter(@Advice.FieldValue("invocation") RpcInvocation invocation) {
      return DECORATE.serverCallOnComplete(invocation);
    }
    //
    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void stopSpan(
        @Advice.Enter final AgentScope scope, @Advice.Thrown final Throwable throwable) {
      if (scope == null) {
        return;
      }
      DECORATE.beforeFinish(scope.span());
      scope.close();
      scope.span().finish();
    }

  }
}
