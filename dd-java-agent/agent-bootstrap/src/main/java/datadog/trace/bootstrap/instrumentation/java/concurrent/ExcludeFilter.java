package datadog.trace.bootstrap.instrumentation.java.concurrent;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class ExcludeFilter {

  public enum ExcludeType {
    RUNNABLE,
    CALLABLE,
    FUTURE,
    FORK_JOIN_TASK,
    EXECUTOR;

    public static ExcludeType fromFieldType(String typeName) {
      switch (typeName) {
        case "java.lang.Runnable":
          return RUNNABLE;
        case "java.util.concurrent.Callable":
          return CALLABLE;
        case "java.util.concurrent.Future":
          return FUTURE;
        case "java.util.concurrent.ForkJoinTask":
          return FORK_JOIN_TASK;
        default:
          return null;
      }
    }
  }

  private static final ExcludeType[] SKIP_TYPE_VALUES = ExcludeType.values();

  public static boolean exclude(ExcludeType type, Object instance) {
    return SKIP.get(instance.getClass()).contains(type);
  }

  public static boolean exclude(ExcludeType type, String className) {
    return excludedClassNames.get(type).contains(className);
  }

  private static final ClassValue<EnumSet<ExcludeType>> SKIP =
      new ClassValue<EnumSet<ExcludeType>>() {
        @Override
        protected EnumSet<ExcludeType> computeValue(Class<?> clazz) {
          EnumSet<ExcludeType> skipTypes = EnumSet.noneOf(ExcludeType.class);
          String name = clazz.getName();
          for (ExcludeType type : SKIP_TYPE_VALUES) {
            if (exclude(type, name)) {
              skipTypes.add(type);
            }
          }
          return skipTypes;
        }
      };

  private static final EnumMap<ExcludeType, Set<String>> excludedClassNames =
      new EnumMap<>(ExcludeType.class);

  static {
    for (ExcludeType type : ExcludeType.values()) {
      excludedClassNames.put(type, new HashSet<String>());
    }
  }

  public static void add(Map<ExcludeType, Set<String>> excludeTypes) {
    if (excludeTypes.isEmpty()) {
      return;
    }
    for (Map.Entry<ExcludeType, Set<String>> entry : excludeTypes.entrySet()) {
      Set<String> currentExcluded = excludedClassNames.get(entry.getKey());
      currentExcluded.addAll(entry.getValue());
    }
  }
}
