package datadog.trace.util;

import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;

/** Shared setup helpers for JMH benchmarks in this module. */
public final class BenchmarkUtils {
  private BenchmarkUtils() {}

  private static final Object[] DEFAULT_DECOY_KEYS = {
    "decoy", 1, 1L, 1.0d, Boolean.TRUE, new Object()
  };

  /**
   * Makes the internal {@code hashCode()} and {@code equals()} call sites of common hash-based
   * collections megamorphic before measurement.
   *
   * <p>HotSpot records receiver classes at each virtual call site. A benchmark using only {@code
   * String} keys leaves the sites inside these shared implementations monomorphic, allowing C2 to
   * devirtualize and inline them. Production uses many key types, so the same sites are often
   * megamorphic and retain virtual dispatch. Exercising several key classes avoids reporting
   * unrealistically fast collection lookups.
   */
  public static void polluteHashDispatch() {
    polluteHashDispatch(DEFAULT_DECOY_KEYS);
  }

  public static void polluteHashDispatch(Object... decoyKeys) {
    populateTypeProfileMutable(new HashSet<>(), decoyKeys);
    populateTypeProfile(CollectionUtils.tryMakeImmutableSet(Arrays.asList(decoyKeys)), decoyKeys);
  }

  /**
   * Exercises {@code contains()} with the default decoy keys.
   *
   * <p>Use the collection implementation under test; the instance itself may be a scratch object.
   * HotSpot stores receiver-type profiles at bytecode call sites, so every instance executing that
   * implementation contributes to the same internal {@code hashCode()} and {@code equals()}
   * profiles. The collection may be mutable or immutable.
   */
  public static void populateTypeProfile(Collection<Object> populated) {
    populateTypeProfile(populated, DEFAULT_DECOY_KEYS);
  }

  public static void populateTypeProfile(Collection<Object> populated, Object... decoyKeys) {
    for (Object key : decoyKeys) {
      populated.contains(key);
    }
  }

  /** Exercises {@code add()} and {@code contains()} on a mutable collection. */
  public static void populateTypeProfileMutable(Collection<Object> scratch, Object... decoyKeys) {
    for (Object key : decoyKeys) {
      scratch.add(key);
      scratch.contains(key);
    }
  }
}
