package datadog.trace.instrumentation.resilience4j;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import io.github.resilience4j.retry.Retry;
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
public class RetryOperatorInstrumentation extends AbstractResilience4jInstrumentation {

  public RetryOperatorInstrumentation() {
    super("resilience4j-retry", "resilience4j-reactor");
  }

  @Override
  public String instrumentedType() {
    return "io.github.resilience4j.reactor.retry.RetryOperator";
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
        RetryOperatorInstrumentation.class.getName() + "$ApplyAdvice");
  }

  @Override
  public Map<String, String> contextStore() {
    final Map<String, String> ret = new HashMap<>();
    ret.put("org.reactivestreams.Subscriber", AgentSpan.class.getName());
    ret.put("org.reactivestreams.Publisher", AgentSpan.class.getName());
    return ret;
  }

  public static class ApplyAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class)
    public static void after(
        @Advice.Argument(value = 0, readOnly = false) Publisher<?> source,
        @Advice.Return(typing = Assigner.Typing.DYNAMIC, readOnly = false) Object result,
        @Advice.FieldValue(value = "retry") Retry retry) {

      if (result instanceof Flux) {
        AgentSpan span = startSpan(retry.getName());

        Flux<?> flux = (Flux<?>) result;

        Flux<?> newResult = flux.doFinally(ReactorHelper.beforeFinish(span));
        if (newResult instanceof Scannable) {
          Scannable parent = (Scannable) newResult;
          // If using putIfAbsent the source publisher should be excluded because it's reused on
          // retry and other publishers are reconstructed
          while (parent != null && parent != source) {
            System.err.println(
                ">>c> Assigning to parent " + parent + " span: " + span.getSpanName());
            // TODO which parent publisher has to be used to assign a span to?

            InstrumentationContext.get(Publisher.class, AgentSpan.class)
                .putIfAbsent((Publisher<?>) parent, span);
            parent = parent.scan(Scannable.Attr.PARENT);
          }
        }
        result = newResult;
      } // TODO mono
    }
  }
}
