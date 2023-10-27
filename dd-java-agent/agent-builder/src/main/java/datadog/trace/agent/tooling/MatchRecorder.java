package datadog.trace.agent.tooling;

import static datadog.trace.agent.tooling.bytebuddy.matcher.ClassLoaderMatchers.ANY_CLASS_LOADER;
import static datadog.trace.agent.tooling.bytebuddy.matcher.ClassLoaderMatchers.hasClassNamed;

import datadog.trace.agent.tooling.context.FieldBackedContextMatcher;
import datadog.trace.api.metrics.InstrumentationMetrics;
import java.util.BitSet;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

/** Records a single match result in the bit-set. */
abstract class MatchRecorder {
  protected final int id;

  MatchRecorder(int id) {
    this.id = id;
  }

  public abstract void record(
      TypeDescription type, ClassLoader classLoader, Class<?> classBeingRedefined, BitSet matches);

  public String describe() {
    return InstrumenterState.describe(id);
  }

  /** Selects types based on a simple direct match that doesn't require further lookup. */
  static final class ForType extends MatchRecorder {
    private final ElementMatcher<TypeDescription> typeMatcher;

    ForType(int id, ElementMatcher<TypeDescription> typeMatcher) {
      super(id);
      this.typeMatcher = typeMatcher;
    }

    @Override
    public void record(
        TypeDescription type,
        ClassLoader classLoader,
        Class<?> classBeingRedefined,
        BitSet matches) {
      long ns = System.nanoTime();
      if (typeMatcher.matches(type)) {
        InstrumentationMetrics.knownTypeHit(ns);
        matches.set(id);
      } else {
        InstrumentationMetrics.knownTypeMiss(ns);
      }
    }
  }

  /** Selects types based on more complex matching against the type's hierarchy. */
  static final class ForHierarchy extends MatchRecorder {
    private final ElementMatcher<ClassLoader> hintMatcher;
    private final ElementMatcher<TypeDescription> typeMatcher;

    ForHierarchy(int id, Instrumenter.ForTypeHierarchy instrumenter) {
      super(id);
      String hint = instrumenter.hierarchyMarkerType();
      this.hintMatcher = null != hint ? hasClassNamed(hint) : ANY_CLASS_LOADER;
      this.typeMatcher = instrumenter.hierarchyMatcher();
    }

    @Override
    public void record(
        TypeDescription type,
        ClassLoader classLoader,
        Class<?> classBeingRedefined,
        BitSet matches) {
      // check current state first in case a known-type already matched this instrumentation
      if (!matches.get(id)) {
        long ns = System.nanoTime();
        if (!hintMatcher.matches(classLoader)) {
          InstrumentationMetrics.classLoaderMiss(ns);
          return;
        } else {
          InstrumentationMetrics.classLoaderHit(ns);
        }
        ns = System.nanoTime();
        if (!typeMatcher.matches(type)) {
          InstrumentationMetrics.typeHierarchyMiss(ns);
          return;
        } else {
          InstrumentationMetrics.typeHierarchyHit(ns);
        }
        matches.set(id);
      }
    }
  }

  /** Selects types that can and should have a context-store field injected. */
  static final class ForContextStore extends MatchRecorder {
    private final ElementMatcher<ClassLoader> activation;
    private final FieldBackedContextMatcher contextMatcher;

    ForContextStore(
        int id, ElementMatcher<ClassLoader> activation, FieldBackedContextMatcher contextMatcher) {
      super(id);
      this.activation = activation;
      this.contextMatcher = contextMatcher;
    }

    @Override
    public void record(
        TypeDescription type,
        ClassLoader classLoader,
        Class<?> classBeingRedefined,
        BitSet matches) {
      long ns = System.nanoTime();
      if (!activation.matches(classLoader)) {
        InstrumentationMetrics.classLoaderMiss(ns);
        return;
      } else {
        InstrumentationMetrics.classLoaderHit(ns);
      }
      ns = System.nanoTime();
      if (!contextMatcher.matches(type, classBeingRedefined)) {
        InstrumentationMetrics.contextStoreMiss(ns);
        return;
      } else {
        InstrumentationMetrics.contextStoreHit(ns);
      }
      matches.set(id);
    }

    @Override
    public String describe() {
      // store description is more useful, as stores cut across instrumentations
      return contextMatcher.describe();
    }
  }

  /** Narrows the current match to eliminate incompatible types. */
  static final class NarrowType extends MatchRecorder {
    private final ElementMatcher<TypeDescription> matcher;

    NarrowType(int id, ElementMatcher<TypeDescription> matcher) {
      super(id);
      this.matcher = matcher;
    }

    @Override
    public void record(
        TypeDescription type,
        ClassLoader classLoader,
        Class<?> classBeingRedefined,
        BitSet matches) {
      if (matches.get(id)) {
        long ns = System.nanoTime();
        if (!matcher.matches(type)) {
          InstrumentationMetrics.narrowTypeHit(ns);
          matches.clear(id);
        } else {
          InstrumentationMetrics.narrowTypeMiss(ns);
        }
      }
    }
  }

  /** Narrows the current match to eliminate incompatible class-loaders. */
  static final class NarrowLocation extends MatchRecorder {
    private final ElementMatcher<ClassLoader> matcher;

    NarrowLocation(int id, ElementMatcher<ClassLoader> matcher) {
      super(id);
      this.matcher = matcher;
    }

    @Override
    public void record(
        TypeDescription type,
        ClassLoader classLoader,
        Class<?> classBeingRedefined,
        BitSet matches) {
      if (matches.get(id)) {
        long ns = System.nanoTime();
        if (!matcher.matches(classLoader)) {
          InstrumentationMetrics.narrowLocationHit(ns);
          matches.clear(id);
        } else {
          InstrumentationMetrics.narrowLocationMiss(ns);
        }
      }
    }
  }
}
