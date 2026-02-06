package datadog.trace.instrumentation.scala213.concurrent;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static java.util.Collections.singletonMap;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static scala.concurrent.impl.Promise.Transformation;

import com.google.auto.service.AutoService;
import datadog.context.Context;
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
 * In Scala 2.13+ there is shortcut that bypass the call to {@code resolve} for a {@code Try} when
 * we know that the value is already resolved, i.e. for some transformations like {@code map}, so
 * only pick up the completing span if the resolved {@code Try} doesn't have a an existing span set
 * from the {@code resolve} method.
 */
@AutoService(InstrumenterModule.class)
public class DefaultPromiseInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

  public DefaultPromiseInstrumentation() {
    super("scala_promise_complete", "scala_concurrent");
  }

  @Override
  public String instrumentedType() {
    return "scala.concurrent.impl.Promise$DefaultPromise";
  }

  @Override
  public Map<String, String> contextStore() {
    return singletonMap("scala.util.Try", Context.class.getName());
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod().and(named("tryComplete0")), getClass().getName() + "$TryComplete");
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

  public static final class TryComplete {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static <T> void beforeTryComplete(
        @Advice.Argument(value = 0) Object state,
        @Advice.Argument(value = 1, readOnly = false) Try<T> resolved) {
      // If the Promise is already completed, then we don't need to do anything
      if (state instanceof Try) {
        return;
      }
      AgentSpan span = PromiseHelper.getSpan();
      if (null != span) {
        ContextStore<Try, Context> contextStore =
            InstrumentationContext.get(Try.class, Context.class);
        Context existing = contextStore.get(resolved);
        if (existing == null) {
          Try<T> next = PromiseHelper.getTry(resolved, span, null);
          if (next != resolved) {
            contextStore.put(next, span);
            resolved = next;
          }
        }
      }
    }

    /** Promise.Transformation was introduced in scala 2.13 */
    private static void muzzleCheck(final Transformation callback) {
      callback.submitWithValue(null);
    }
  }
}
