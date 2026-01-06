package datadog.trace.instrumentation.scala213.concurrent;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static java.util.Collections.singletonMap;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static scala.concurrent.impl.Promise.Transformation;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.api.InstrumenterConfig;
import datadog.trace.bootstrap.ContextStore;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.instrumentation.scala.PromiseHelper;
import java.util.Collections;
import java.util.Map;
import net.bytebuddy.asm.Advice;
import scala.util.Try;

/**
 * A Scala {@code Promise} is always completed with a {@code Try}, so if we want the completing span
 * to take priority over any spans captured while adding computations to a {@code Future} associated
 * with a {@code Promise}, then we capture the active span when the {@code Try} is resolved.
 */
@AutoService(InstrumenterModule.class)
public class PromiseObjectInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

  public PromiseObjectInstrumentation() {
    super("scala_promise_resolve", "scala_concurrent");
  }

  @Override
  public String instrumentedType() {
    // The $ at the end is how Scala encodes a Scala object (as opposed to a class or trait)
    return "scala.concurrent.impl.Promise$";
  }

  @Override
  public Map<String, String> contextStore() {
    return singletonMap("scala.util.Try", AgentSpan.class.getName());
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod().and(named("scala$concurrent$impl$Promise$$resolve")),
        getClass().getName() + "$Resolve");
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {"datadog.trace.instrumentation.scala.PromiseHelper"};
  }

  @Override
  public boolean isEnabled() {
    // Only enable this if integrations have been enabled and the extra "integration"
    // scala_promise_completion_priority has been enabled specifically
    return super.isEnabled()
        && InstrumenterConfig.get()
            .isIntegrationEnabled(
                Collections.singletonList("scala_promise_completion_priority"), false);
  }

  public static final class Resolve {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static <T> void afterResolve(@Advice.Return(readOnly = false) Try<T> resolved) {
      AgentSpan span = PromiseHelper.getSpan();
      if (null != span) {
        ContextStore<Try, AgentSpan> contextStore =
            InstrumentationContext.get(Try.class, AgentSpan.class);
        Try<T> next = PromiseHelper.getTry(resolved, span, contextStore.get(resolved));
        if (next != resolved) {
          contextStore.put(next, span);
          resolved = next;
        }
      }
    }

    /** Promise.Transformation was introduced in scala 2.13 */
    private static void muzzleCheck(final Transformation callback) {
      callback.submitWithValue(null);
    }
  }
}
