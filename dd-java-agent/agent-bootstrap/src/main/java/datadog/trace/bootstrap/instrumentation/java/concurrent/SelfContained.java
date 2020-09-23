package datadog.trace.bootstrap.instrumentation.java.concurrent;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

public class SelfContained {
  public static boolean skip(Object instance) {
    return SKIP.get(instance.getClass());
  }

  public static boolean skip(String className) {
    return selfContainedClasses.contains(className);
  }

  private static final ClassValue<Boolean> SKIP =
      new ClassValue<Boolean>() {
        @Override
        protected Boolean computeValue(Class<?> type) {
          return selfContainedClasses.contains(type.getName());
        }
      };

  // TODO add config here for different instrumentations
  private static final Set<String> selfContainedClasses;

  static {
    selfContainedClasses = new HashSet<>();
    String[] classes = {
      // This is not a subclass of UniCompletion and doesn't have a dependent CompletableFuture
      "java.util.concurrent.CompletableFuture$Completion",
      "java.util.concurrent.CompletableFuture$UniCompletion",
      "java.util.concurrent.CompletableFuture$UniApply",
      "java.util.concurrent.CompletableFuture$UniAccept",
      "java.util.concurrent.CompletableFuture$UniRun",
      "java.util.concurrent.CompletableFuture$UniWhenComplete",
      "java.util.concurrent.CompletableFuture$UniHandle",
      "java.util.concurrent.CompletableFuture$UniExceptionally",
      "java.util.concurrent.CompletableFuture$UniComposeExceptionally",
      "java.util.concurrent.CompletableFuture$UniRelay",
      "java.util.concurrent.CompletableFuture$UniCompose",
      "java.util.concurrent.CompletableFuture$BiCompletion",
      // This is not a subclass of UniCompletion and doesn't have a dependent CompletableFuture
      "java.util.concurrent.CompletableFuture$CoCompletion",
      "java.util.concurrent.CompletableFuture$BiApply",
      "java.util.concurrent.CompletableFuture$BiAccept",
      "java.util.concurrent.CompletableFuture$BiRun",
      "java.util.concurrent.CompletableFuture$BiRelay",
      "java.util.concurrent.CompletableFuture$OrApply",
      "java.util.concurrent.CompletableFuture$OrAccept",
      "java.util.concurrent.CompletableFuture$OrRun",
      // This is not a subclass of UniCompletion and doesn't have a dependent CompletableFuture
      "java.util.concurrent.CompletableFuture$AnyOf",
      // This is not a subclass of UniCompletion and doesn't have a dependent CompletableFuture
      "java.util.concurrent.CompletableFuture$Signaller",
    };
    selfContainedClasses.addAll(Arrays.asList(classes));
  }
}
