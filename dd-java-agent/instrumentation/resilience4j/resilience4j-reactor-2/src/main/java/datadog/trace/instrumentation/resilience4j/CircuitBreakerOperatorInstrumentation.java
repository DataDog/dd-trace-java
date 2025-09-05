package datadog.trace.instrumentation.resilience4j;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
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
import org.reactivestreams.Publisher;

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
    ret.put("org.reactivestreams.Publisher", AgentSpan.class.getName());
    return ret;
  }

  public static class ApplyAdvice {

    @Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class)
    public static void after(
        @Advice.Argument(value = 0, readOnly = false) Publisher<?> source,
        @Advice.Return(readOnly = false) Publisher<?> result,
        @Advice.FieldValue(value = "circuitBreaker") CircuitBreaker circuitBreaker) {

      result =
          ReactorHelper.wrap(
              result,
              CircuitBreakerDecorator.DECORATE,
              circuitBreaker,
              InstrumentationContext.get(Publisher.class, AgentSpan.class)::put);
    }
  }
}
