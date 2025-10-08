package datadog.trace.instrumentation.java.concurrent.virtualthread;

import static java.util.Collections.singletonMap;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.named;

import com.google.auto.service.AutoService;
import datadog.environment.JavaVirtualMachine;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.api.Stateful;
import datadog.trace.bootstrap.ContextStore;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.bootstrap.instrumentation.api.ProfilerContext;
import java.util.Map;
import net.bytebuddy.asm.Advice;

/**
 * Instruments the VirtualThread class to enable profiler context propagation on vthread
 * mount/unmount.
 *
 * <ul>
 *   <li>https://github.com/openjdk/jdk/blob/6a4c2676a6378f573bd58d1bc32b57765d756291/src/java.base/share/classes/java/lang/VirtualThread.java#L478
 *   <li>https://github.com/openjdk/jdk/blob/6a4c2676a6378f573bd58d1bc32b57765d756291/src/java.base/share/classes/java/lang/VirtualThread.java#L508
 * </ul>
 */
@AutoService(InstrumenterModule.class)
public class ThreadContinuationInstrumentation extends InstrumenterModule.Profiling
    implements Instrumenter.ForBootstrap, Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {
  public ThreadContinuationInstrumentation() {
    super("java_concurrent", "vthread-continuation");
  }

  @Override
  public String instrumentedType() {
    return "java.lang.VirtualThread";
  }

  @Override
  public boolean isEnabled() {
    return JavaVirtualMachine.isJavaVersionAtLeast(19) && super.isEnabled();
  }

  @Override
  public Map<String, String> contextStore() {
    return singletonMap(Thread.class.getName(), Stateful.class.getName());
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(isMethod().and(named("mount")), getClass().getName() + "$Mount");
    transformer.applyAdvice(isMethod().and(named("unmount")), getClass().getName() + "$Unmount");
  }

  public static final class Mount {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void onMount(@Advice.FieldValue("carrierThread") Thread carrier) {
      ContextStore<Thread, Stateful> contextStore =
          InstrumentationContext.get(Thread.class, Stateful.class);
      // The TLS has been rerouted after 'mount' - we can get the vthread relative active span
      AgentSpan span = AgentTracer.activeSpan();
      // we are fully mounted and carrier points to the correct OS thread
      Stateful stateful = contextStore.get(carrier);
      ProfilerContext ctx = (ProfilerContext) span.context();
      if (stateful == null) {
        stateful = AgentTracer.get().getProfilingContext().newScopeState(ctx);
        // associate the stateful with the carrier thread
        contextStore.put(carrier, stateful);
      } else {
        // just make sure we really close all statefuls
        stateful.close();
      }
      // activate the stateful with the proper context and propagate the context to profiler
      stateful.activate(ctx);
    }
  }

  public static final class Unmount {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void onUnmount(@Advice.FieldValue("carrierThread") Thread carrier) {
      ContextStore<Thread, Stateful> contextStore =
          InstrumentationContext.get(Thread.class, Stateful.class);
      // The carrier thread still points to the carrying thread; we have not unmounted yet
      Stateful stateful = contextStore.get(carrier);
      if (stateful != null) {
        // close the stateful, clean up the profiling context
        stateful.close();
      }
    }
  }
}
