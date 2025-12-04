package datadog.trace.instrumentation.dubbo_3_2;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.nameStartsWith;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.instrumentation.dubbo_3_2.DubboDecorator.DECORATE;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import org.apache.dubbo.rpc.TriRpcStatus;
import org.apache.dubbo.rpc.protocol.tri.RequestMetadata;

@AutoService(InstrumenterModule.class)
public class DubboTripleClientCallInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForTypeHierarchy, Instrumenter.HasMethodAdvice {

  private static final String CLASS_NAME = "org.apache.dubbo.rpc.protocol.tri.call.TripleClientCall";

  public DubboTripleClientCallInstrumentation() {
    super("apache-dubbo","dubbo-client-call");
  }

  @Override
  public String hierarchyMarkerType() {
    return CLASS_NAME;
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return named(hierarchyMarkerType());
  }

  @Override
  public String[] helperClassNames() {

    return new String[] {
        packageName + ".DubboObserverToClientCallAdvice",
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
        isMethod().and(nameStartsWith("onComplete")).and(takesArgument(0, named("org.apache.dubbo.rpc.TriRpcStatus"))),
        DubboTripleClientCallInstrumentation.class.getName() + "$CompleteAdvice");
  }

  public static class CompleteAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static AgentScope onEnter(@Advice.Argument(0) TriRpcStatus status,@Advice.FieldValue("requestMetadata") RequestMetadata requestMetadata) {
      return DECORATE.clientComplete(requestMetadata,status);
    }
    //
    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void stopSpan(
        @Advice.Enter final AgentScope scope,@Advice.Argument(0) TriRpcStatus status, @Advice.Thrown final Throwable throwable) {
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
