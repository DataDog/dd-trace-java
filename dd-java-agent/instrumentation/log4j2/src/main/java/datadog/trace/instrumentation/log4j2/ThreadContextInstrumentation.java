package datadog.trace.instrumentation.log4j2;

import static datadog.trace.agent.tooling.ClassLoaderMatcher.hasClassesNamed;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isTypeInitializer;
import static net.bytebuddy.matcher.ElementMatchers.not;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.api.Config;
import datadog.trace.api.WithGlobalTracer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(Instrumenter.class)
public final class ThreadContextInstrumentation extends Instrumenter.Tracing {
  private static final String TYPE_NAME = "org.apache.logging.log4j.ThreadContext";

  public ThreadContextInstrumentation() {
    super("log4j", "log4j-2");
  }

  @Override
  protected boolean defaultEnabled() {
    return Config.get().isLogsInjectionEnabled();
  }

  @Override
  public ElementMatcher.Junction<ClassLoader> classLoaderMatcher() {
    // ContextDataInjectorFactory is in log4j 2.7+. That has its own instrumentation
    return not(hasClassesNamed("org.apache.logging.log4j.core.impl.ContextDataInjectorFactory"));
  }

  @Override
  public ElementMatcher<? super TypeDescription> typeMatcher() {
    return named(TYPE_NAME);
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        isTypeInitializer(), ThreadContextInstrumentation.class.getName() + "$ThreadContextAdvice");
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      "datadog.trace.agent.tooling.log.LogContextScopeListener",
      "datadog.trace.instrumentation.log4j2.ThreadContextUpdater"
    };
  }

  public static final class ThreadContextAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void mdcClassInitialized() {
      WithGlobalTracer.registerOrExecute(new ThreadContextUpdater());
    }
  }
}
