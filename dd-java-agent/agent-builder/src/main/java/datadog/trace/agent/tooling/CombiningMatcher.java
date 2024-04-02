package datadog.trace.agent.tooling;

import static datadog.trace.api.config.TraceInstrumentationConfig.EXPERIMENTAL_DEFER_INTEGRATIONS_UNTIL;
import static datadog.trace.util.AgentThreadFactory.AgentThread.RETRANSFORMER;

import datadog.trace.agent.tooling.bytebuddy.matcher.CustomExcludes;
import datadog.trace.agent.tooling.bytebuddy.matcher.ProxyClassIgnores;
import datadog.trace.api.InstrumenterConfig;
import datadog.trace.util.AgentTaskScheduler;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.utility.JavaModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Combines separate matcher results into a single bit-set for {@link SplittingTransformer}. */
final class CombiningMatcher implements AgentBuilder.RawMatcher {
  private static final Logger log = LoggerFactory.getLogger(CombiningMatcher.class);

  private static final boolean DEFER_MATCHING =
      null != InstrumenterConfig.get().deferIntegrationsUntil();

  private static final Set<String> DEFERRED_CLASSLOADER_NAMES =
      InstrumenterConfig.get().getDeferredClassLoaders();

  private static final boolean DEFER_ALL = DEFERRED_CLASSLOADER_NAMES.isEmpty();

  // optimization to avoid repeated allocations inside BitSet as matched ids are set
  static final int MAX_COMBINED_ID_HINT = 512;

  /** Matcher results shared between {@link CombiningMatcher} and {@link SplittingTransformer} */
  static final ThreadLocal<BitSet> recordedMatches =
      ThreadLocal.withInitial(() -> new BitSet(MAX_COMBINED_ID_HINT));

  private final KnownTypesIndex knownTypesIndex = KnownTypesIndex.readIndex();

  private final BitSet knownTypesMask;
  private final MatchRecorder[] matchers;

  private volatile boolean deferring;

  CombiningMatcher(
      Instrumentation instrumentation, BitSet knownTypesMask, List<MatchRecorder> matchers) {
    this.knownTypesMask = knownTypesMask;
    this.matchers = matchers.toArray(new MatchRecorder[0]);

    if (DEFER_MATCHING) {
      scheduleResumeMatching(instrumentation, InstrumenterConfig.get().deferIntegrationsUntil());
    }
  }

  @Override
  public boolean matches(
      TypeDescription target,
      ClassLoader classLoader,
      JavaModule module,
      Class<?> classBeingRedefined,
      ProtectionDomain pd) {

    // check initial requests to see if we should defer matching until retransformation
    if (DEFER_MATCHING && null == classBeingRedefined && deferring && isDeferred(classLoader)) {
      return false;
    }

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

  /** Arranges for any deferred matching to resume at the requested trigger point. */
  private void scheduleResumeMatching(Instrumentation instrumentation, String untilTrigger) {
    if (null != untilTrigger && !untilTrigger.isEmpty()) {
      Pattern delayPattern = Pattern.compile("(\\d+)([HhMmSs]?)");
      Matcher delayMatcher = delayPattern.matcher(untilTrigger);
      if (delayMatcher.matches()) {
        long delay = Integer.parseInt(delayMatcher.group(1));
        String unit = delayMatcher.group(2);
        if ("H".equalsIgnoreCase(unit)) {
          delay = TimeUnit.HOURS.toSeconds(delay);
        } else if ("M".equalsIgnoreCase(unit)) {
          delay = TimeUnit.MINUTES.toSeconds(delay);
        } else {
          // already in seconds
        }

        if (delay < 5) {
          return; // don't bother deferring small delays
        }

        new AgentTaskScheduler(RETRANSFORMER)
            .schedule(this::resumeMatching, instrumentation, delay, TimeUnit.SECONDS);

        deferring = true;
      } else {
        log.info(
            "Unrecognized value for dd.{}: {}",
            EXPERIMENTAL_DEFER_INTEGRATIONS_UNTIL,
            untilTrigger);
      }
    }
  }

  /**
   * Scans loaded classes to find which ones we should retransform to resume matching them.
   *
   * <p>We try to only trigger retransformations for classes we know would match. Caching and
   * memoization means running matching twice is cheaper than unnecessary retransformations.
   */
  void resumeMatching(Instrumentation instrumentation) {
    if (!deferring) {
      return;
    }

    deferring = false;

    Iterator<Iterable<Class<?>>> rediscovery =
        AgentStrategies.rediscoveryStrategy().resolve(instrumentation).iterator();

    List<Class<?>> resuming = new ArrayList<>();
    while (rediscovery.hasNext()) {
      for (Class<?> clazz : rediscovery.next()) {
        ClassLoader classLoader = clazz.getClassLoader();
        if (isDeferred(classLoader)
            && !wouldIgnore(clazz.getName())
            && instrumentation.isModifiableClass(clazz)
            && wouldMatch(classLoader, clazz)) {
          resuming.add(clazz);
        }
      }
    }

    try {
      log.debug("Resuming deferred matching for {}", resuming);
      instrumentation.retransformClasses(resuming.toArray(new Class[0]));
    } catch (Throwable e) {
      log.debug("Problem resuming deferred matching", e);
    }
  }

  /**
   * Tests whether matches involving this class-loader should be deferred until later.
   *
   * <p>The bootstrap class-loader is never deferred.
   */
  private static boolean isDeferred(ClassLoader classLoader) {
    return null != classLoader
        && (DEFER_ALL || DEFERRED_CLASSLOADER_NAMES.contains(classLoader.getClass().getName()));
  }

  /** Tests whether this class would be ignored on retransformation. */
  private static boolean wouldIgnore(String name) {
    return name.indexOf('/') >= 0 // don't retransform lambdas
        || CustomExcludes.isExcluded(name)
        || ProxyClassIgnores.isIgnored(name);
  }

  /** Tests whether this class would be matched at least once on retransformation. */
  private boolean wouldMatch(ClassLoader classLoader, Class<?> clazz) {
    BitSet ids = recordedMatches.get();
    ids.clear();

    knownTypesIndex.apply(clazz.getName(), knownTypesMask, ids);
    if (!ids.isEmpty()) {
      return true;
    }

    TypeDescription target = new TypeDescription.ForLoadedType(clazz);

    for (MatchRecorder matcher : matchers) {
      try {
        matcher.record(target, classLoader, clazz, ids);
        if (!ids.isEmpty()) {
          return true;
        }
      } catch (Throwable ignore) {
        // skip misbehaving matchers
      }
    }

    return false;
  }
}
