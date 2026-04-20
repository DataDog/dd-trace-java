package datadog.trace.instrumentation.zio.v2_0;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static java.util.Collections.singletonMap;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.ExcludeFilterProvider;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.bootstrap.ContextStore;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.java.concurrent.ExcludeFilter;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import net.bytebuddy.asm.Advice;
import zio.Fiber;
import zio.Supervisor;

@AutoService(InstrumenterModule.class)
public class ZioRuntimeInstrumentation extends InstrumenterModule.ContextTracking
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice, ExcludeFilterProvider {

  public ZioRuntimeInstrumentation() {
    super("zio.experimental");
  }

  @Override
  public String instrumentedType() {
    return "zio.Runtime$";
  }

  @Override
  protected final boolean defaultEnabled() {
    return false;
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod().and(named("defaultSupervisor")), getClass().getName() + "$DefaultSupervisor");
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {packageName + ".FiberContext", packageName + ".TracingSupervisor"};
  }

  @Override
  public Map<String, String> contextStore() {
    return singletonMap("zio.Fiber$Runtime", packageName + ".FiberContext");
  }

  @Override
  public Map<ExcludeFilter.ExcludeType, ? extends Collection<String>> excludedClasses() {
    return Collections.singletonMap(
        ExcludeFilter.ExcludeType.RUNNABLE, Collections.singletonList("zio.internal.FiberRuntime"));
  }

  public static final class DefaultSupervisor {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void onExit(@Advice.Return(readOnly = false) Supervisor<?> supervisor) {
      @SuppressWarnings("rawtypes")
      ContextStore<Fiber.Runtime, FiberContext> contextStore =
          InstrumentationContext.get(Fiber.Runtime.class, FiberContext.class);
      supervisor = supervisor.$plus$plus(new TracingSupervisor(contextStore));
    }
  }
}
