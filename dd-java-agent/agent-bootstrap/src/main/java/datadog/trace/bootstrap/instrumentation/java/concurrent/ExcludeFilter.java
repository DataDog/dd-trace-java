package datadog.trace.bootstrap.instrumentation.java.concurrent;

import datadog.trace.api.GenericClassValue;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Enables types to opt out of being wrapped and/or having fields injected for a number of the broad
 * instrumentations, i.e. {@code Executor} and {@code Runnable}.
 */
public class ExcludeFilter {

  public enum ExcludeType {
    RUNNABLE,
    FORK_JOIN_TASK,
    RUNNABLE_FUTURE,
    EXECUTOR;

    public static ExcludeType fromFieldType(String typeName) {
      switch (typeName) {
        case "java.lang.Runnable":
          return RUNNABLE;
        case "java.util.concurrent.ForkJoinTask":
          return FORK_JOIN_TASK;
        case "java.util.concurrent.RunnableFuture":
          return RUNNABLE_FUTURE;
        case "java.util.concurrent.Executor":
          return EXECUTOR;
        default:
          return null;
      }
    }
  }

  private static final ExcludeType[] SKIP_TYPE_VALUES = ExcludeType.values();
  private static final Map<ExcludeType, List<String>> SKIP_TYPE_PREFIXES =
      new EnumMap<>(ExcludeType.class);

  public static boolean exclude(ExcludeType type, Object instance) {
    return SKIP.get(instance.getClass()).contains(type);
  }

  public static boolean exclude(ExcludeType type, String className) {
    boolean literalMatch = excludedClassNames.get(type).contains(className);
    if (literalMatch) {
      return true;
    }
    List<String> excludedPrefixes = SKIP_TYPE_PREFIXES.get(type);
    if (null != excludedPrefixes) {
      for (String prefix : excludedPrefixes) {
        if (className.startsWith(prefix)) {
          return true;
        }
      }
    }
    return false;
  }

  private static EnumSet<ExcludeType> exclude(Class<?> clazz) {
    EnumSet<ExcludeType> skipTypes = EnumSet.noneOf(ExcludeType.class);
    String name = clazz.getName();
    for (ExcludeType type : SKIP_TYPE_VALUES) {
      if (exclude(type, name)) {
        skipTypes.add(type);
      } else {
        for (String prefix : SKIP_TYPE_PREFIXES.get(type)) {
          if (name.startsWith(prefix)) {
            skipTypes.add(type);
          }
        }
      }
    }
    return skipTypes;
  }

  private static final ClassValue<EnumSet<ExcludeType>> SKIP =
      GenericClassValue.of(ExcludeFilter::exclude);

  private static final EnumMap<ExcludeType, Set<String>> excludedClassNames =
      new EnumMap<>(ExcludeType.class);

  static {
    for (ExcludeType type : ExcludeType.values()) {
      excludedClassNames.put(type, new HashSet<>());
    }
    for (ExcludeType type : SKIP_TYPE_VALUES) {
      SKIP_TYPE_PREFIXES.put(type, new ArrayList<>());
    }
    // TODO generic prefix registration
    SKIP_TYPE_PREFIXES.get(ExcludeType.RUNNABLE).add("slick.");
    // Don't instrument the executor's own runnables. These runnables may never return until
    // netty shuts down.
    SKIP_TYPE_PREFIXES
        .get(ExcludeType.EXECUTOR)
        .add("io.netty.util.concurrent.SingleThreadEventExecutor.");
    // Don't wrap Runnables belonging to NioEventLoop(s) as they want to propagate CloseException
    // outside of the event loop on close() and wrapping them in FutureTask interferes with that
    SKIP_TYPE_PREFIXES.get(ExcludeType.RUNNABLE).add("com.aerospike.client.async.NioEventLoop");
    // exclude various ForkJoinTasks internal to CHM
    SKIP_TYPE_PREFIXES
        .get(ExcludeType.FORK_JOIN_TASK)
        .add("java.util.concurrent.ConcurrentHashMap");
    // Exclude Runnables in the Google code ConcurrentLinkedHashMap
    SKIP_TYPE_PREFIXES
        .get(ExcludeType.RUNNABLE)
        .add("com.googlecode.concurrentlinkedhashmap.ConcurrentLinkedHashMap");
    SKIP_TYPE_PREFIXES
        .get(ExcludeType.RUNNABLE)
        .add("org.springframework.kafka.listener.KafkaMessageListenerContainer$ListenerConsumer$");
  }

  /**
   * This should only be called during startup to initialize this based on information gathered from
   * the {@code Instrumenter} instances.
   *
   * @param excludeTypes the types to exclude
   */
  public static void add(Map<ExcludeType, ? extends Collection<String>> excludeTypes) {
    if (excludeTypes.isEmpty()) {
      return;
    }
    for (Map.Entry<ExcludeType, ? extends Collection<String>> entry : excludeTypes.entrySet()) {
      Set<String> currentExcluded = excludedClassNames.get(entry.getKey());
      currentExcluded.addAll(entry.getValue());
    }
  }
}
