package datadog.trace.agent.tooling.bytebuddy;

import static datadog.trace.agent.tooling.bytebuddy.matcher.ClassLoaderMatchers.canSkipClassLoaderByName;

import datadog.trace.agent.tooling.bytebuddy.matcher.IgnoredClassNameTrie;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;
import net.bytebuddy.agent.builder.AgentBuilder.RedefinitionStrategy;

/**
 * Selects specific classes loaded during agent installation that we want to re-transform.
 *
 * <p>This handles a situation where our ByteBuddy transformer won't be notified of "definitions of
 * classes upon which any registered transformer is dependent". These classes are already loaded so
 * we cannot make structural changes, but we can still add method advice by re-transforming them.
 *
 * <p>Each round of re-transformation can result in more types to re-transform, so the iterator
 * repeats until no more types need re-transforming. Typically only one or two rounds are needed
 * before we run out of types to re-transform, but we impose a hard limit as a precaution.
 *
 * @see Instrumentation#addTransformer(ClassFileTransformer, boolean)
 */
public final class DDRediscoveryStrategy implements RedefinitionStrategy.DiscoveryStrategy {
  static final int MAX_ROUNDS = 10;

  @Override
  public Iterable<Iterable<Class<?>>> resolve(final Instrumentation instrumentation) {
    return new Iterable<Iterable<Class<?>>>() {
      @Override
      public Iterator<Iterable<Class<?>>> iterator() {
        final Set<Class<?>> visited = new HashSet<>(256);
        return new Iterator<Iterable<Class<?>>>() {
          private int round = 0;

          @Override
          public boolean hasNext() {
            return round < MAX_ROUNDS;
          }

          @Override
          public Iterable<Class<?>> next() {
            if (!hasNext()) {
              throw new NoSuchElementException();
            }
            List<Class<?>> next = selectClassesForRetransformation(instrumentation, visited);
            if (next.isEmpty()) {
              round = MAX_ROUNDS; // halt iterator, nothing more to re-transform
            } else {
              visited.addAll(next); // mark as visited so they're not transformed again
              round++;
            }
            return next;
          }

          @Override
          public void remove() {
            throw new UnsupportedOperationException();
          }
        };
      }
    };
  }

  /** Selects classes to retransform from already loaded classes that we haven't previously seen. */
  static List<Class<?>> selectClassesForRetransformation(
      final Instrumentation instrumentation, final Set<Class<?>> visited) {
    List<Class<?>> retransforming = new ArrayList<>();
    for (Class<?> clazz : instrumentation.getAllLoadedClasses()) {
      ClassLoader classLoader = clazz.getClassLoader();
      if (null != classLoader) {
        if (canSkipClassLoaderByName(classLoader)) {
          continue;
        }
        Class<?> loaderClass = classLoader.getClass();
        // postpone transforming types if their class-loader has yet to be transformed
        // (this stops us from accidentally marking the class-loader as skipped when we
        // haven't yet patched it to delegate to the boot-class-path for tracer types)
        if (!visited.contains(loaderClass) && allowRetransform(loaderClass)) {
          continue;
        }
      }
      if (!visited.contains(clazz) && allowRetransform(clazz)) {
        retransforming.add(clazz);
      }
    }
    return retransforming;
  }

  private static boolean allowRetransform(Class<?> clazz) {
    switch (IgnoredClassNameTrie.apply(clazz.getName())) {
      case 0:
        return true; // explicitly allowed
      case -1:
        // unknown type; if it's from the boot-class-path ignore it, otherwise allow
        return null != clazz.getClassLoader();
      default:
        return false; // explicitly ignored
    }
  }
}
