package datadog.trace.instrumentation.micrometer.context;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isStatic;
import static net.bytebuddy.matcher.ElementMatchers.takesNoArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import io.micrometer.context.ContextRegistry;
import java.util.concurrent.atomic.AtomicBoolean;
import net.bytebuddy.asm.Advice;

/**
 * Instruments {@link ContextRegistry#getInstance()} to register the Datadog {@link
 * DatadogThreadLocalAccessor} when the registry is first accessed.
 *
 * <p>This ensures that Datadog trace context is automatically propagated when using Reactor's
 * automatic context propagation with virtual threads or other reactive schedulers.
 */
@AutoService(InstrumenterModule.class)
public class ContextRegistryInstrumentation extends InstrumenterModule.ContextTracking
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

  public ContextRegistryInstrumentation() {
    super("micrometer-context-propagation");
  }

  @Override
  public String instrumentedType() {
    return "io.micrometer.context.ContextRegistry";
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".DatadogThreadLocalAccessor",
    };
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod().and(isStatic()).and(named("getInstance")).and(takesNoArguments()),
        ContextRegistryInstrumentation.class.getName() + "$RegisterAccessorAdvice");
  }

  public static class RegisterAccessorAdvice {
    private static final AtomicBoolean registered = new AtomicBoolean(false);

    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void onExit(@Advice.Return ContextRegistry registry) {
      if (registered.compareAndSet(false, true)) {
        registry.registerThreadLocalAccessor(new DatadogThreadLocalAccessor());
      }
    }
  }
}
