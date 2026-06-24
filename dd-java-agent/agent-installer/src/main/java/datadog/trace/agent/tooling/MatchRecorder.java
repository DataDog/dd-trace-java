package datadog.trace.agent.tooling;

import static datadog.trace.agent.tooling.bytebuddy.matcher.ClassLoaderMatchers.ANY_CLASS_LOADER;
import static datadog.trace.agent.tooling.bytebuddy.matcher.ClassLoaderMatchers.hasClassNamed;

import datadog.trace.agent.tooling.context.FieldBackedContextMatcher;
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
      long fromTick = InstrumenterMetrics.tick();
      if (typeMatcher.matches(type)) {
        InstrumenterMetrics.knownTypeHit(fromTick);
        matches.set(id);
      } else {
        InstrumenterMetrics.knownTypeMiss(fromTick);
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
        long fromTick = InstrumenterMetrics.tick();
        if (hintMatcher.matches(classLoader) && typeMatcher.matches(type)) {
          InstrumenterMetrics.typeHierarchyHit(fromTick);
          matches.set(id);
        } else {
          InstrumenterMetrics.typeHierarchyMiss(fromTick);
        }
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
      long fromTick = InstrumenterMetrics.tick();
      if (activation.matches(classLoader) && contextMatcher.matches(type, classBeingRedefined)) {
        InstrumenterMetrics.contextStoreHit(fromTick);
        matches.set(id);
      } else {
        InstrumenterMetrics.contextStoreMiss(fromTick);
      }
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
        long fromTick = InstrumenterMetrics.tick();
        if (!matcher.matches(type)) {
          InstrumenterMetrics.narrowTypeMiss(fromTick);
          matches.clear(id);
        } else {
          InstrumenterMetrics.narrowTypeHit(fromTick);
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
        long fromTick = InstrumenterMetrics.tick();
        if (!matcher.matches(classLoader)) {
          InstrumenterMetrics.narrowLocationMiss(fromTick);
          matches.clear(id);
        } else {
          InstrumenterMetrics.narrowLocationHit(fromTick);
        }
      }
    }
  }
}
