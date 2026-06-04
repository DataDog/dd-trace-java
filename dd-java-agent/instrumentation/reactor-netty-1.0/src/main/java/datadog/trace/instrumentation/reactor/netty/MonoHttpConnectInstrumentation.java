package datadog.trace.instrumentation.reactor.netty;

import static datadog.trace.agent.tooling.bytebuddy.matcher.ClassLoaderMatchers.hasClassNamed;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.isAsyncPropagationEnabled;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.setAsyncPropagationEnabled;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.matcher.ElementMatcher;
import reactor.core.CoreSubscriber;
import reactor.netty.Connection;

/**
 * Suppresses generic async captures created while Reactor Netty subscribes to connection setup.
 *
 * <p>The subscriber is wrapped first so the active span is still available from Reactor context;
 * {@link TransferConnectSpan} later turns that context value into the continuation consumed by
 * Netty request tracing.
 */
@AutoService(InstrumenterModule.class)
public class MonoHttpConnectInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

  public MonoHttpConnectInstrumentation() {
    super("reactor-netty", "reactor-netty-1");
  }

  @Override
  public ElementMatcher.Junction<ClassLoader> classLoaderMatcher() {
    // Avoid matching pre-1.0 releases which are not compatible.
    return hasClassNamed("reactor.netty.transport.AddressUtils");
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".ConnectSpanSubscriber",
    };
  }

  @Override
  public String instrumentedType() {
    return "reactor.netty.http.client.HttpClientConnect$MonoHttpConnect";
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        named("subscribe").and(takesArgument(0, named("reactor.core.CoreSubscriber"))),
        getClass().getName() + "$SubscribeAdvice");
  }

  public static class SubscribeAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static boolean before(
        @Advice.Argument(value = 0, readOnly = false)
            CoreSubscriber<? super Connection> subscriber) {
      final AgentSpan span = activeSpan();
      if (span != null) {
        subscriber = new ConnectSpanSubscriber(subscriber, span);
      }
      if (isAsyncPropagationEnabled()) {
        setAsyncPropagationEnabled(false);
        return true;
      }
      return false;
    }

    @Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class)
    public static void after(@Advice.Enter final boolean wasDisabled) {
      if (wasDisabled) {
        setAsyncPropagationEnabled(true);
      }
    }
  }
}
