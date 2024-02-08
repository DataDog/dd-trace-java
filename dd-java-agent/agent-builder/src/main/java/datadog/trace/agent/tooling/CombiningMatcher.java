package datadog.trace.agent.tooling;

import java.security.ProtectionDomain;
import java.util.BitSet;
import java.util.List;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.utility.JavaModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Combines separate matcher results into a single bit-set for {@link SplittingTransformer}. */
final class CombiningMatcher implements AgentBuilder.RawMatcher {
  private static final Logger log = LoggerFactory.getLogger(CombiningMatcher.class);

  // optimization to avoid repeated allocations inside BitSet as matched ids are set
  static final int MAX_COMBINED_ID_HINT = 512;

  /** Matcher results shared between {@link CombiningMatcher} and {@link SplittingTransformer} */
  static final ThreadLocal<BitSet> recordedMatches =
      ThreadLocal.withInitial(() -> new BitSet(MAX_COMBINED_ID_HINT));

  private final BitSet knownTypesMask;
  private final MatchRecorder[] matchers;

  private static final KnownTypesIndex knownTypesIndex = KnownTypesIndex.readIndex();

  CombiningMatcher(BitSet knownTypesMask, List<MatchRecorder> matchers) {
    this.knownTypesMask = knownTypesMask;
    this.matchers = matchers.toArray(new MatchRecorder[0]);
  }

  @Override
  public boolean matches(
      TypeDescription target,
      ClassLoader classLoader,
      JavaModule module,
      Class<?> classBeingRedefined,
      ProtectionDomain pd) {

    BitSet ids = recordedMatches.get();
    ids.clear();

    long fromTick = InstrumenterMetrics.tick();
    knownTypesIndex.apply(target.getName(), knownTypesMask, ids);
    if (ids.isEmpty()) {
      InstrumenterMetrics.knownTypeMiss(fromTick);
    } else {
      InstrumenterMetrics.knownTypeHit(fromTick);
    }

    for (MatchRecorder matcher : matchers) {
      try {
        matcher.record(target, classLoader, classBeingRedefined, ids);
      } catch (Throwable e) {
        if (log.isDebugEnabled()) {
          log.debug("Instrumentation matcher unexpected exception - {}", matcher.describe(), e);
        }
      }
    }

    InstrumenterMetrics.matchType(fromTick);

    return !ids.isEmpty();
  }
}
