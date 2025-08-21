package datadog.trace.instrumentation.resilience4j;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.implementation.bytecode.assign.Assigner;
import org.reactivestreams.Publisher;
import reactor.core.Scannable;
import reactor.core.publisher.Flux;

@AutoService(InstrumenterModule.class)
public class CircuitBreakerOperatorInstrumentation extends AbstractResilience4jInstrumentation {

  public CircuitBreakerOperatorInstrumentation() {
    super("resilience4j-circuitbreaker", "resilience4j-reactor");
  }

  @Override
  public String instrumentedType() {
    return "io.github.resilience4j.reactor.circuitbreaker.operator.CircuitBreakerOperator";
  }

  @Override
  public String[] helperClassNames() {
    ArrayList<String> ret = new ArrayList<>();

    ret.add(packageName + ".ReactorHelper");

    ret.addAll(Arrays.asList(super.helperClassNames()));

    return ret.toArray(new String[0]);
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod()
            .and(named("apply"))
            .and(takesArgument(0, named("org.reactivestreams.Publisher"))),
        CircuitBreakerOperatorInstrumentation.class.getName() + "$ApplyAdvice");
  }

  @Override
  public Map<String, String> contextStore() {
    final Map<String, String> ret = new HashMap<>();
    //    ret.put("org.reactivestreams.Subscriber", AgentSpan.class.getName());
    ret.put("org.reactivestreams.Publisher", AgentSpan.class.getName());
    return ret;
  }

  public static class ApplyAdvice {

    @Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class)
    public static void after(
        @Advice.Argument(value = 0, readOnly = false) Publisher<?> source,
        @Advice.Return(typing = Assigner.Typing.DYNAMIC, readOnly = false) Object result,
        @Advice.FieldValue(value = "circuitBreaker") CircuitBreaker circuitBreaker) {
      if (result instanceof Flux) {
        AgentSpan span = startSpan(circuitBreaker.getName());

        Flux<?> flux = (Flux<?>) result;

        // TODO maybe start span in doFirst? then we would need a span holder
        Flux<?> newResult = flux.doFinally(ReactorHelper.beforeFinish(span));

        if (newResult instanceof Scannable) {
          Scannable parent = (Scannable) newResult;
          // If using putIfAbsent the source publisher should be excluded because it's reused on
          // retry and other publishers are reconstructed
          // Don't assign to the source as it's reused on retry
          while (parent != null) {
            InstrumentationContext.get(Publisher.class, AgentSpan.class)
                .put((Publisher<?>) parent, span);
            parent = parent.scan(Scannable.Attr.PARENT);
          }
        }

        // for the circuit breaker publisher we should only assign a span to the source publisher,
        // which is just enough
        // whereas for the retry we shouldn't attach to the source because it's reused
        // TODO test if it will work with an open circuit breaker
        //        InstrumentationContext.get(Publisher.class, AgentSpan.class).putIfAbsent(source,
        // span);
        result = newResult;
      } // TODO mono
    }
  }
}
