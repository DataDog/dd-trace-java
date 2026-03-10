package datadog.trace.instrumentation.playws;

import static datadog.trace.agent.tooling.InstrumenterModule.TargetSystem.CONTEXT_TRACKING;
import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.implementsInterface;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.nameStartsWith;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;
import static datadog.trace.bootstrap.instrumentation.api.Java8BytecodeBridge.getCurrentContext;
import static datadog.trace.instrumentation.playws.HeadersInjectAdapter.SETTER;
import static datadog.trace.instrumentation.playws.PlayWSClientDecorator.DECORATE;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.not;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.agent.tooling.annotation.AppliesOn;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import play.shaded.ahc.org.asynchttpclient.Request;

public abstract class BasePlayWSClientInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForTypeHierarchy, Instrumenter.HasMethodAdvice {
  public BasePlayWSClientInstrumentation() {
    super("play-ws");
  }

  @Override
  public String hierarchyMarkerType() {
    return "play.shaded.ahc.org.asynchttpclient.AsyncHttpClient";
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    // CachingAsyncHttpClient rejects overrides to AsyncHandler
    // It also delegates to another AsyncHttpClient
    return nameStartsWith("play.")
        .and(
            implementsInterface(named(hierarchyMarkerType()))
                .and(not(named("play.api.libs.ws.ahc.cache.CachingAsyncHttpClient"))));
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvices(
        isMethod()
            .and(named("execute"))
            .and(takesArguments(2))
            .and(takesArgument(0, named("play.shaded.ahc.org.asynchttpclient.Request")))
            .and(takesArgument(1, named("play.shaded.ahc.org.asynchttpclient.AsyncHandler"))),
        getClass().getName() + "$ClientAdvice",
        BasePlayWSClientInstrumentation.class.getName() + "$ClientContextPropagationAdvice");
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      "datadog.trace.instrumentation.playws.PlayWSClientDecorator",
      "datadog.trace.instrumentation.playws.HeadersInjectAdapter",
      packageName + ".AsyncHandlerWrapper",
      packageName + ".StreamedAsyncHandlerWrapper"
    };
  }

  @AppliesOn(CONTEXT_TRACKING)
  public static class ClientContextPropagationAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void methodEnter(@Advice.Argument(0) final Request request) {
      AgentSpan span = activeSpan();
      if (span == null) {
        return;
      }
      DECORATE.injectContext(getCurrentContext().with(span), request, SETTER);
    }
  }
}
