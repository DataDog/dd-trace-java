package datadog.trace.instrumentation.log4j2;

import static datadog.trace.agent.tooling.bytebuddy.matcher.ClassLoaderMatchers.hasClassNamed;
import static net.bytebuddy.matcher.ElementMatchers.isTypeInitializer;
import static net.bytebuddy.matcher.ElementMatchers.not;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.api.WithGlobalTracer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(InstrumenterModule.class)
public class ThreadContextInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {
  private static final String TYPE_NAME = "org.apache.logging.log4j.ThreadContext";

  public ThreadContextInstrumentation() {
    super("log4j", "log4j-2");
  }

  @Override
  public ElementMatcher.Junction<ClassLoader> classLoaderMatcher() {
    // Avoid matching log4j 2.7+ which has its own instrumentation.
    return not(hasClassNamed("org.apache.logging.log4j.core.impl.ContextDataInjectorFactory"));
  }

  @Override
  public String instrumentedType() {
    return TYPE_NAME;
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isTypeInitializer(), ThreadContextInstrumentation.class.getName() + "$ThreadContextAdvice");
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      "datadog.trace.agent.tooling.log.LogContextScopeListener",
      "datadog.trace.instrumentation.log4j2.ThreadContextUpdater"
    };
  }

  @Override
  public String muzzleDirective() {
    return "logs-context";
  }

  public static class ThreadContextAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void mdcClassInitialized() {
      WithGlobalTracer.registerOrExecute(new ThreadContextUpdater());
    }
  }
}
