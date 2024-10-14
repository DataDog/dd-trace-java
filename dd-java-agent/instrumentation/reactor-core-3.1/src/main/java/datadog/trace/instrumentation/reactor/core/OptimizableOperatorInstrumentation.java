package datadog.trace.instrumentation.reactor.core;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.hasInterface;
import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.implementsInterface;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.returns;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.util.HashMap;
import java.util.Map;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;

/**
 * This instrumentation is responsible for transferring the {@link AgentSpan} when subscription
 * optimization are made. In particular reactor's OptimizableOperators can do subscription via a
 * loop call instead of recursion.
 */
@AutoService(InstrumenterModule.class)
public class OptimizableOperatorInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForTypeHierarchy {

  public OptimizableOperatorInstrumentation() {
    super("reactor-core");
  }

  @Override
  public String hierarchyMarkerType() {
    return "reactor.core.publisher.OptimizableOperator";
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return implementsInterface(named(hierarchyMarkerType()));
  }

  @Override
  public Map<String, String> contextStore() {
    final Map<String, String> ret = new HashMap<>();
    ret.put("org.reactivestreams.Subscriber", AgentSpan.class.getName());
    ret.put("org.reactivestreams.Publisher", AgentSpan.class.getName());
    return ret;
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod()
            .and(named("subscribeOrReturn"))
            .and(takesArguments(1))
            .and(returns(hasInterface(named("org.reactivestreams.Subscriber")))),
        getClass().getName() + "$PublisherSubscribeAdvice");
  }

  public static class PublisherSubscribeAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void onSubscribe(
        @Advice.This final Publisher self,
        @Advice.Argument(0) final Subscriber arg,
        @Advice.Return final Subscriber s) {
      if (s == null || arg == null) {
        return;
      }
      AgentSpan span = InstrumentationContext.get(Publisher.class, AgentSpan.class).get(self);
      if (span == null) {
        span = InstrumentationContext.get(Subscriber.class, AgentSpan.class).get(arg);
      }
      if (span != null) {
        InstrumentationContext.get(Subscriber.class, AgentSpan.class).putIfAbsent(s, span);
      }
    }
  }
}
