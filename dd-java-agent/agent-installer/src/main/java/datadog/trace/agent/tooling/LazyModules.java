package datadog.trace.agent.tooling;

import java.util.Arrays;
import java.util.BitSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tracks instrumentation modules whose loading is deferred until one of their known types is first
 * matched, and the matchers published to the {@link CombiningMatcher}.
 *
 * <p>Matching threads only read immutable snapshots: the set of transformation ids that still
 * belong to unloaded modules, the ids of modules that turned out to be unusable, and the current
 * matchers. Loading a module happens at most once, under the builder's lock, and then publishes new
 * snapshots; ids that aren't pending cost one snapshot read per matched type.
 */
final class LazyModules {
  private static final Logger log = LoggerFactory.getLogger(LazyModules.class);

  private final CombiningTransformerBuilder builder;

  // indexed by transformation id; only written before installation
  private InstrumenterIndex.LazyModule[] byTransformationId = new InstrumenterIndex.LazyModule[0];

  // immutable snapshots, replaced as modules are loaded
  private volatile BitSet pending = new BitSet();
  private volatile BitSet unusable = new BitSet();
  private volatile MatchRecorder[] matchers;

  LazyModules(CombiningTransformerBuilder builder) {
    this.builder = builder;
  }

  /**
   * Registers a lazy module before installation; not thread-safe. Returns {@code false} if it was
   * already registered, e.g. seen on a second pass over the index.
   */
  boolean register(InstrumenterIndex.LazyModule lazyModule) {
    if (lazyModule.transformationCount <= 0 || lazyModule.firstTransformationId < 0) {
      return false;
    }
    int end = lazyModule.firstTransformationId + lazyModule.transformationCount;
    if (byTransformationId.length < end) {
      byTransformationId = Arrays.copyOf(byTransformationId, end);
    }
    if (null != byTransformationId[lazyModule.firstTransformationId]) {
      return false;
    }
    BitSet nextPending = (BitSet) pending.clone();
    for (int id = lazyModule.firstTransformationId; id < end; id++) {
      byTransformationId[id] = lazyModule;
      nextPending.set(id);
    }
    pending = nextPending;
    return true;
  }

  /** Whether any modules were registered for lazy loading. */
  boolean isEmpty() {
    return byTransformationId.length == 0;
  }

  void publishMatchers(MatchRecorder[] matchers) {
    this.matchers = matchers;
  }

  /** The latest published matchers, or {@code null} if none were published. */
  MatchRecorder[] matchers() {
    return matchers;
  }

  /**
   * Loads any pending modules among the matched transformation ids, then removes ids of modules
   * that couldn't be used from the matches.
   */
  void loadMatched(BitSet ids) {
    BitSet currentPending = pending;
    if (currentPending.intersects(ids)) {
      for (int id = ids.nextSetBit(0); id >= 0; id = ids.nextSetBit(id + 1)) {
        if (currentPending.get(id)) {
          load(byTransformationId[id]);
        }
      }
    }
    BitSet currentUnusable = unusable;
    if (!currentUnusable.isEmpty()) {
      ids.andNot(currentUnusable);
    }
  }

  private void load(InstrumenterIndex.LazyModule lazyModule) {
    synchronized (builder) {
      if (!pending.get(lazyModule.firstTransformationId)) {
        return; // another thread loaded it first
      }
      boolean applied = builder.applyLazyInstrumentation(lazyModule);
      BitSet nextPending = (BitSet) pending.clone();
      BitSet nextUnusable = applied ? unusable : (BitSet) unusable.clone();
      int end = lazyModule.firstTransformationId + lazyModule.transformationCount;
      for (int id = lazyModule.firstTransformationId; id < end; id++) {
        nextPending.clear(id);
        if (!applied) {
          nextUnusable.set(id);
        }
      }
      unusable = nextUnusable;
      pending = nextPending; // published last: matchers and advice are in place by now
      if (log.isDebugEnabled()) {
        log.debug(
            "Lazily {} instrumentation module {}",
            applied ? "loaded" : "skipped",
            lazyModule.moduleName);
      }
    }
  }
}
