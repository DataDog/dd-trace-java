package excludefilter;

import static datadog.trace.bootstrap.instrumentation.java.concurrent.ExcludeFilter.ExcludeType.EXECUTOR;
import static datadog.trace.bootstrap.instrumentation.java.concurrent.ExcludeFilter.ExcludeType.RUNNABLE;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.ExcludeFilterProvider;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.bootstrap.instrumentation.java.concurrent.ExcludeFilter;
import java.util.Arrays;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executor;

@AutoService(InstrumenterModule.class)
public class ExcludeFilterTestInstrumentation extends InstrumenterModule.Tracing
    implements ExcludeFilterProvider {

  public ExcludeFilterTestInstrumentation() {
    super("excludefilter-test");
  }

  @Override
  public Map<String, String> contextStore() {
    Map<String, String> contextStores = new HashMap<>();
    contextStores.put(Runnable.class.getName(), Object.class.getName());
    contextStores.put(Executor.class.getName(), Object.class.getName());
    return contextStores;
  }

  @Override
  public Map<ExcludeFilter.ExcludeType, ? extends Collection<String>> excludedClasses() {
    EnumMap<ExcludeFilter.ExcludeType, Collection<String>> excludedTypes =
        new EnumMap<>(ExcludeFilter.ExcludeType.class);
    String prefix = getClass().getName() + "$";
    excludedTypes.put(
        RUNNABLE, Arrays.asList(prefix + "ExcludedRunnable", prefix + "ExecutorExcludedRunnable"));
    excludedTypes.put(
        EXECUTOR, Arrays.asList(prefix + "ExcludedExecutor", prefix + "RunnableExcludedExecutor"));
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

  public static final class ExcludedExecutor implements Executor {
    @Override
    public void execute(Runnable command) {}
  }

  public static final class NormalExecutor implements Executor {
    @Override
    public void execute(Runnable command) {}
  }

  public static final class RunnableExcludedExecutor implements Executor, Runnable {
    @Override
    public void execute(Runnable command) {}

    @Override
    public void run() {}
  }

  public static final class ExecutorExcludedRunnable implements Executor, Runnable {

    @Override
    public void run() {}

    @Override
    public void execute(Runnable command) {}
  }

  public static final class ExecutorRunnable implements Executor, Runnable {

    @Override
    public void run() {}

    @Override
    public void execute(Runnable command) {}
  }
}
