package datadog.trace.instrumentation.springboot;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.instrumentation.java.concurrent.ExcludeFilter.ExcludeType.RUNNABLE;
import static net.bytebuddy.matcher.ElementMatchers.isConstructor;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.ExcludeFilterProvider;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.api.InstrumenterConfig;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.classloading.MemoizerResetter;
import datadog.trace.bootstrap.instrumentation.java.concurrent.ExcludeFilter;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import net.bytebuddy.asm.Advice;
import org.springframework.boot.devtools.restart.classloader.RestartClassLoader;

@AutoService(InstrumenterModule.class)
public class RestartClassLoaderInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForSingleType, ExcludeFilterProvider {

  public RestartClassLoaderInstrumentation() {
    super("spring-boot-devtools", "spring-boot");
  }

  @Override
  public boolean isEnabled() {
    return super.isEnabled() && InstrumenterConfig.get().isResolverMemoizingEnabled();
  }

  @Override
  public Map<String, String> contextStore() {
    return Collections.singletonMap(
        "org.springframework.boot.devtools.restart.classloader.RestartClassLoader",
        "java.lang.Boolean");
  }

  @Override
  public String instrumentedType() {
    return "org.springframework.boot.devtools.restart.classloader.RestartClassLoader";
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(isConstructor(), getClass().getName() + "$RecordNewClassloaderAdvice");
    transformer.applyAdvice(
        isMethod().and(named("findClass")), getClass().getName() + "$ResetMemoizerAdvice");
  }

  @Override
  public Map<ExcludeFilter.ExcludeType, ? extends Collection<String>> excludedClasses() {
    return Collections.singletonMap(
        RUNNABLE,
        Collections.singleton(
            "org.springframework.boot.devtools.restart.Restarter$LeakSafeThread"));
  }

  public static class RecordNewClassloaderAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void after(@Advice.This RestartClassLoader self) {
      InstrumentationContext.get(RestartClassLoader.class, Boolean.class).putIfAbsent(self, true);
    }
  }

  public static class ResetMemoizerAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void before(@Advice.This RestartClassLoader self) {
      if (Boolean.TRUE.equals(
          InstrumentationContext.get(RestartClassLoader.class, Boolean.class).remove(self))) {
        MemoizerResetter.Supplier.reset();
      }
    }
  }

  @Override
  public String muzzleDirective() {
    return "devtools";
  }
}
