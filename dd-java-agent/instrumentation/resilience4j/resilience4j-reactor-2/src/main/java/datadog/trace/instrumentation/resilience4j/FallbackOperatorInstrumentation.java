package datadog.trace.instrumentation.resilience4j;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.returns;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;
import net.bytebuddy.asm.Advice;
import org.reactivestreams.Publisher;

@AutoService(InstrumenterModule.class)
public class FallbackOperatorInstrumentation extends AbstractResilience4jInstrumentation {

  public FallbackOperatorInstrumentation() {
    super("resilience4j-fallback", "resilience4j-reactor");
  }

  @Override
  public String instrumentedType() {
    return "io.github.resilience4j.reactor.ReactorOperatorFallbackDecorator";
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
            .and(named("decorate"))
            .and(
                takesArgument(0, named("java.util.function.UnaryOperator"))
                    .and(returns(named("java.util.function.Function")))),
        FallbackOperatorInstrumentation.class.getName() + "$DecorateAdvice");
  }

  @Override
  public Map<String, String> contextStore() {
    final Map<String, String> ret = new HashMap<>();
    ret.put("org.reactivestreams.Publisher", AgentSpan.class.getName());
    return ret;
  }

  public static class DecorateAdvice {

    @Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class)
    public static void after(
        //        @Advice.Argument(value = 0, readOnly = false) UnaryOperator<Publisher<?>>
        // operator,
        @Advice.Return(readOnly = false) Function<Publisher<?>, Publisher<?>> result) {

      result =
          ReactorHelper.wrapFunction(
              result, InstrumentationContext.get(Publisher.class, AgentSpan.class)::putIfAbsent);
    }
  }
}
