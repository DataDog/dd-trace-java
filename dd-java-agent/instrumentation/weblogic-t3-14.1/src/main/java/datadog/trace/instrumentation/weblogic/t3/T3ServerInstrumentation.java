package datadog.trace.instrumentation.weblogic.t3;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.extendsClass;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.returns;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.context.ContextScope;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import weblogic.rjvm.MsgAbbrevInputStream;

@AutoService(InstrumenterModule.class)
public final class T3ServerInstrumentation extends InstrumenterModule.ContextTracking
    implements Instrumenter.ForTypeHierarchy, Instrumenter.HasMethodAdvice {
  public T3ServerInstrumentation() {
    super("weblogic-t3");
  }

  @Override
  protected boolean defaultEnabled() {
    return false;
  }

  @Override
  public String hierarchyMarkerType() {
    return "weblogic.rmi.internal.Skeleton";
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return extendsClass(named(hierarchyMarkerType()));
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        named("invoke")
            .and(takesArguments(4))
            .and(takesArgument(0, int.class))
            .and(takesArgument(1, named("weblogic.rmi.spi.InboundRequest")))
            .and(takesArgument(2, named("weblogic.rmi.spi.OutboundResponse")))
            .and(takesArgument(3, Object.class))
            .and(returns(named("weblogic.rmi.spi.OutboundResponse")))
            .and(method -> !method.isAbstract()),
        getClass().getName() + "$InvokeAdvice");
  }

  public static class InvokeAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static ContextScope onEnter(@Advice.Argument(1) Object request) throws Exception {
      if (!(request instanceof MsgAbbrevInputStream)
          || ((MsgAbbrevInputStream) request).isCollocated()) {
        return null;
      }
      return T3WorkContext.extract();
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void onExit(@Advice.Enter ContextScope scope) {
      if (scope != null) {
        scope.close();
      }
    }
  }
}
