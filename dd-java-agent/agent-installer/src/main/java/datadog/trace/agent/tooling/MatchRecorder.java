package datadog.trace.agent.tooling;

import static datadog.trace.agent.tooling.bytebuddy.matcher.ClassLoaderMatchers.ANY_CLASS_LOADER;
import static datadog.trace.agent.tooling.bytebuddy.matcher.ClassLoaderMatchers.hasClassNamed;
import static datadog.trace.util.CollectionUtils.arrayContains;

import datadog.trace.agent.tooling.bytebuddy.matcher.ConstantPoolSieve;
import datadog.trace.agent.tooling.context.FieldBackedContextMatcher;
import datadog.trace.api.InstrumenterConfig;
import java.util.BitSet;
import java.util.List;
import java.util.Set;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
    private static final Logger log = LoggerFactory.getLogger(ForHierarchy.class);

    private static final boolean VERIFY_SIEVE =
        InstrumenterConfig.get().isResolverDeclaredMethodSieveVerify();

    private final ElementMatcher<ClassLoader> hintMatcher;
    private final ElementMatcher<TypeDescription> typeMatcher;

    // optional gate: skip types whose constant pool has none of the advised method names
    private Set<String> declaredMethodNames;
    private List<ElementMatcher<? super MethodDescription>> advisedMethods;
    private int[] declaredNameIds;

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
        if (null != declaredNameIds && DeclaredMethodGate.excludes(declaredNameIds)) {
          if (VERIFY_SIEVE) {
            verifySkip(type, classLoader);
          }
          InstrumenterMetrics.typeHierarchySieved(fromTick);
          return;
        }
        if (hintMatcher.matches(classLoader) && typeMatcher.matches(type)) {
          InstrumenterMetrics.typeHierarchyHit(fromTick);
          matches.set(id);
        } else {
          InstrumenterMetrics.typeHierarchyMiss(fromTick);
        }
      }
    }

    /** Checks that a type skipped by the gate really declares none of the advised methods. */
    private void verifySkip(TypeDescription type, ClassLoader classLoader) {
      long fromTick = InstrumenterMetrics.tick();
      try {
        if (hintMatcher.matches(classLoader) && typeMatcher.matches(type)) {
          for (MethodDescription method : type.getDeclaredMethods()) {
            for (ElementMatcher<? super MethodDescription> advised : advisedMethods) {
              if (advised.matches(method)) {
                InstrumenterMetrics.typeHierarchySieveMissed(fromTick);
                log.warn(
                    "Declared-method sieve skipped {} for {} but it declares advised method {}",
                    type.getName(),
                    describe(),
                    method);
                return;
              }
            }
          }
        }
      } catch (Throwable e) {
        log.debug("Declared-method sieve verification failed for {}", type.getName(), e);
      }
    }

    /**
     * Limits this matcher to types whose constant pool contains at least one of the given names,
     * i.e. types that may declare an advised method. Only valid for instrumentations whose advice
     * is all method advice, since method advice only applies to declared methods.
     */
    void gate(
        Set<String> declaredMethodNames,
        List<ElementMatcher<? super MethodDescription>> advisedMethods) {
      this.declaredMethodNames = declaredMethodNames;
      this.advisedMethods = advisedMethods;
    }

    /** Names this matcher is gated on, or {@code null} if it isn't gated. */
    Set<String> declaredMethodNames() {
      return declaredMethodNames;
    }

    /** Resolves the gate's names to ids in the shared sieve; must be called before matching. */
    void assignIds(ConstantPoolSieve sieve) {
      if (null != declaredMethodNames) {
        int[] ids = new int[declaredMethodNames.size()];
        int i = 0;
        for (String name : declaredMethodNames) {
          ids[i++] = sieve.id(name);
        }
        declaredNameIds = ids;
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

  /** Narrows the current match to avoid structural changes on already-loaded classes. */
  static final class PreserveLoadedStructure extends MatchRecorder {
    private final Class<?> structuralChangeMarker;

    PreserveLoadedStructure(int id, Class<?> structuralChangeMarker) {
      super(id);
      this.structuralChangeMarker = structuralChangeMarker;
    }

    @Override
    public void record(
        TypeDescription type,
        ClassLoader classLoader,
        Class<?> classBeingRedefined,
        BitSet matches) {
      // don't transform loaded classes unless they directly declare the marker
      // - we must re-transform those to preserve the original structural change
      if (matches.get(id)
          && null != classBeingRedefined
          && !arrayContains(classBeingRedefined.getInterfaces(), structuralChangeMarker)) {
        matches.clear(id);
      }
    }
  }

  /** Copies an already-computed match into another id, avoiding a redundant type match. */
  static final class CopyMatch extends MatchRecorder {
    private final int fromId;

    CopyMatch(int fromId, int toId) {
      super(toId);
      if (toId <= fromId) {
        // matchers are evaluated in 'id' order; we can only copy from ids strictly before this id
        throw new IllegalArgumentException("fromId " + fromId + " must be before toId " + toId);
      }
      this.fromId = fromId;
    }

    @Override
    public void record(
        TypeDescription type,
        ClassLoader classLoader,
        Class<?> classBeingRedefined,
        BitSet matches) {
      if (matches.get(fromId)) {
        matches.set(id);
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
