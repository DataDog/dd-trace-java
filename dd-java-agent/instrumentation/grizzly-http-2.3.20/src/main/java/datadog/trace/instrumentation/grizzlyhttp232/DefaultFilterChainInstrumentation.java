package datadog.trace.instrumentation.grizzlyhttp232;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;
import static datadog.trace.bootstrap.instrumentation.api.Java8BytecodeBridge.spanFromContext;
import static datadog.trace.bootstrap.instrumentation.decorator.HttpServerDecorator.DD_CONTEXT_ATTRIBUTE;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isPrivate;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import datadog.context.Context;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.api.InstrumenterConfig;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.util.Collections;
import net.bytebuddy.asm.Advice;
import org.glassfish.grizzly.filterchain.FilterChainContext;

@AutoService(InstrumenterModule.class)
public class DefaultFilterChainInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

  public DefaultFilterChainInstrumentation() {
    super("grizzly-filterchain");
  }

  @Override
  public String instrumentedType() {
    return "org.glassfish.grizzly.filterchain.DefaultFilterChain";
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".GrizzlyDecorator",
      packageName + ".GrizzlyDecorator$GrizzlyHttpBlockResponseFunction",
      packageName + ".GrizzlyHttpBlockingHelper",
      packageName + ".GrizzlyHttpBlockingHelper$CloseCompletionHandler",
      packageName + ".GrizzlyHttpBlockingHelper$JustCompleteProcessor",
      packageName + ".HTTPRequestPacketURIDataAdapter",
      packageName + ".ExtractAdapter"
    };
  }

  @Override
  protected boolean defaultEnabled() {
    return InstrumenterConfig.get().isIntegrationEnabled(Collections.singleton("mule"), false);
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod()
            .and(isPrivate())
            .and(named("notifyFailure"))
            .and(takesArgument(0, named("org.glassfish.grizzly.filterchain.FilterChainContext")))
            .and(takesArgument(1, named("java.lang.Throwable"))),
        packageName + ".DefaultFilterChainAdvice");
    transformer.applyAdvice(
        isMethod()
            .and(named("executeFilter"))
            .and(takesArgument(2, named("org.glassfish.grizzly.filterchain.FilterChainContext"))),
        getClass().getName() + "$PropagateServerSpanAdvice");
  }

  public static class PropagateServerSpanAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static AgentScope onEnter(@Advice.Argument(2) final FilterChainContext ctx) {
      final AgentSpan active = activeSpan();
      // don't activate a span if already one is active
      if (active != null) {
        return null;
      }
      final Object contextObj = ctx.getAttributes().getAttribute(DD_CONTEXT_ATTRIBUTE);
      if (contextObj instanceof Context) {
        final Context context = (Context) contextObj;
        final AgentSpan span = spanFromContext(context);
        if (span != null) {
          // activate the http server span when nothing is already active
          return activateSpan(span);
        }
      }
      return null;
    }

    @Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class)
    public static void onExit(@Advice.Enter final AgentScope scope) {
      if (scope != null) {
        scope.close();
      }
    }
  }
}
