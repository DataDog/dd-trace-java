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
   * Exercises {@link HashSet}/{@link java.util.HashMap} and the tracer's {@link
   * CollectionUtils#tryMakeImmutableSet} immutable sets with several distinct key classes, so their
   * internal {@code hashCode()}/{@code equals()} dispatch -- a call site shared JVM-wide by every
   * instance of that structure in the process, regardless of which specific instance or call site
   * invokes {@code add}/{@code contains} -- is already megamorphic before a benchmark measures
   * lookups against a single key type.
   *
   * <p>This matches production: those shared internal call sites are hit by every hash-based
   * structure in the JVM across whatever key types the whole application uses, so they're
   * realistically almost always megamorphic. An isolated benchmark that only ever looks up one key
   * type (e.g. {@code String}) would otherwise leave them artificially monomorphic for the entire
   * run, understating real dispatch cost.
   *
   * <p>Deliberately does not touch the benchmark's own {@code contains}/{@code add} call sites --
   * those are realistically free to specialize per caller, the way a genuinely hot, narrowly-typed
   * call site would in production.
   *
   * <p>Not to be confused with the CHA-defeat decoys in {@code SingleThreadedMapBenchmark}/{@code
   * ThreadSafeMapBenchmark} ({@code KeyStrategy} implementors referenced only so they're loaded,
   * never invoked): that technique denies class-hierarchy analysis a single-implementor bet for a
   * narrow, dd-trace-java-owned interface, and works by class-loading alone. It doesn't apply here
   * -- {@code Object.hashCode()}/{@code equals()} already have countless implementors loaded in any
   * real JVM, so a single-implementor CHA bet was never available for them. What gates their
   * dispatch is the interpreter's per-call-site type profile, which only invocation can pollute --
   * hence this helper actually calls {@code add}/{@code contains}, rather than just loading
   * classes.
   */
  public static void polluteHashDispatch() {
    polluteHashDispatch(DEFAULT_DECOY_KEYS);
  }

  public static void polluteHashDispatch(Object... decoyKeys) {
    populateTypeProfileMutable(new HashSet<>(), decoyKeys);
    populateTypeProfile(CollectionUtils.tryMakeImmutableSet(Arrays.asList(decoyKeys)), decoyKeys);
  }

  /**
   * The entry point most benchmarks should reach for: pass the same kind of collection instance
   * under test (or an equivalent scratch instance). Works for both mutable and immutable
   * collections since it only drives {@code contains()} -- the operation every {@link
   * java.util.Set} supports, and the one these lookup benchmarks actually measure.
   */
  public static void populateTypeProfile(Collection<Object> populated) {
    populateTypeProfile(populated, DEFAULT_DECOY_KEYS);
  }

  public static void populateTypeProfile(Collection<Object> populated, Object... decoyKeys) {
    for (Object key : decoyKeys) {
      populated.contains(key);
    }
  }

  /**
   * Lower-level control: also drives {@code add()} dispatch, so {@code scratch} must genuinely
   * support mutation, and lets the caller pick the decoy keys. Reach for this only when {@code
   * add()} dispatch matters too, or the default decoys aren't the right shape.
   */
  public static void populateTypeProfileMutable(Collection<Object> scratch, Object... decoyKeys) {
    for (Object key : decoyKeys) {
      scratch.add(key);
      scratch.contains(key);
    }
  }
}
