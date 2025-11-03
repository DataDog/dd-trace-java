package datadog.trace.instrumentation.dubbo_3_2;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import org.apache.dubbo.rpc.TriRpcStatus;
import org.apache.dubbo.rpc.protocol.tri.call.ClientCall;
import org.apache.dubbo.rpc.protocol.tri.call.TripleClientCall;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.nameStartsWith;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.instrumentation.dubbo_3_2.DubboDecorator.DECORATE;
import static net.bytebuddy.matcher.ElementMatchers.*;

@AutoService(InstrumenterModule.class)
public class DubboObserverToClientCallInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForTypeHierarchy, Instrumenter.HasMethodAdvice {

  private static final String CLASS_NAME = "org.apache.dubbo.rpc.protocol.tri.call.ObserverToClientCallListenerAdapter";

  public DubboObserverToClientCallInstrumentation() {
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
        isMethod()
            .and(isPublic())
            .and(nameStartsWith("onMessage")),
        packageName + ".DubboObserverToClientCallAdvice");
    transformer.applyAdvice(
        isMethod().and(nameStartsWith("onClose")).and(takesArgument(0, named("org.apache.dubbo.rpc.TriRpcStatus"))),
        DubboObserverToClientCallInstrumentation.class.getName() + "$CloseAdvice");
  }

  public static class CloseAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static AgentScope onEnter(@Advice.Argument(0) TriRpcStatus status,@Advice.FieldValue("call") ClientCall call) {
      TripleClientCall tripleClientCall = (TripleClientCall) call ;
      return DECORATE.obToClientCallClose(tripleClientCall,status);
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
