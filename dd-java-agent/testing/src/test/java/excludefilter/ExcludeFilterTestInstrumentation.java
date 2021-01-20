package excludefilter;

import static datadog.trace.bootstrap.instrumentation.java.concurrent.ExcludeFilter.ExcludeType.*;
import static net.bytebuddy.matcher.ElementMatchers.none;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.ExcludeFilterProvider;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.instrumentation.java.concurrent.ExcludeFilter;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Callable;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(Instrumenter.class)
public class ExcludeFilterTestInstrumentation extends Instrumenter.Tracing
    implements ExcludeFilterProvider {

  public ExcludeFilterTestInstrumentation() {
    super("excludefilter-test");
  }

  @Override
  public ElementMatcher<? super TypeDescription> typeMatcher() {
    return none();
  }

  @Override
  public Map<? extends ElementMatcher<? super MethodDescription>, String> transformers() {
    return Collections.emptyMap();
  }

  @Override
  public Map<String, String> contextStoreForAll() {
    Map<String, String> contextStores = new HashMap<>();
    contextStores.put(Runnable.class.getName(), Object.class.getName());
    contextStores.put(Callable.class.getName(), Object.class.getName());
    return contextStores;
  }

  @Override
  public Map<ExcludeFilter.ExcludeType, ? extends Collection<String>> excludedClasses() {
    EnumMap<ExcludeFilter.ExcludeType, Collection<String>> excludedTypes =
        new EnumMap<>(ExcludeFilter.ExcludeType.class);
    String prefix = getClass().getName() + "$";
    excludedTypes.put(
        RUNNABLE, Arrays.asList(prefix + "ExcludedRunnable", prefix + "CallableExcludedRunnable"));
    excludedTypes.put(
        CALLABLE,
        Arrays.asList(
            prefix + "ExcludedCallable",
            prefix + "RunnableExcludedCallable",
            "net.sf.cglib.core.internal.LoadingCache$2" // Excluded for global ignore matcher
            ));
    return excludedTypes;
  }

  public static final class ExcludedRunnable implements Runnable {
    @Override
    public void run() {}
  }

  public static final class NormalRunnable implements Runnable {
    @Override
    public void run() {}
  }

  public static final class ExcludedCallable implements Callable<Void> {
    @Override
    public Void call() throws Exception {
      return null;
    }
  }

  public static final class NormalCallable implements Callable<Void> {
    @Override
    public Void call() throws Exception {
      return null;
    }
  }

  public static final class RunnableExcludedCallable implements Callable<Void>, Runnable {
    @Override
    public Void call() throws Exception {
      return null;
    }

    @Override
    public void run() {}
  }

  public static final class CallableExcludedRunnable implements Callable<Void>, Runnable {
    @Override
    public Void call() throws Exception {
      return null;
    }

    @Override
    public void run() {}
  }

  public static final class CallableRunnable implements Callable<Void>, Runnable {
    @Override
    public Void call() throws Exception {
      return null;
    }

    @Override
    public void run() {}
  }
}
