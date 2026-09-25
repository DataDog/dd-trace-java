package datadog.trace.util;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.BiPredicate;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.ToLongBiFunction;
import java.util.function.ToLongFunction;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.CompilerControl;
import org.openjdk.jmh.infra.Blackhole;

/**
 * Shared setup helpers for JMH benchmarks in this module.
 *
 * <p>These exist to compensate for forking. JMH runs each benchmark method in a fresh JVM, which is
 * right for measurement -- one arm cannot contaminate another's numbers -- but it means every arm
 * is compiled inside a process that has only ever executed that one code path, with that one arm's
 * key types and that one arm's branch outcomes. Production is the reverse: a single JVM runs
 * everything. So the harness hands C2 a maximally specialized view of the world, and the
 * compilation that results can be better than anything achievable in a real application. Put
 * another way, JMH forks to eliminate cross-benchmark profile pollution, but profile pollution
 * <i>is</i> the production condition.
 *
 * <p>That surfaces three ways. This class addresses the first two partially and the third not at
 * all:
 *
 * <ul>
 *   <li><b>Receiver-type profiles</b> collapse toward a single key type, so shared JDK dispatch
 *       sites look monomorphic. {@link #warmUpHashDispatch} drives several key classes through
 *       them.
 *   <li><b>Class-hierarchy analysis</b> sees only the implementations one arm happens to load, so
 *       C2 can devirtualize calls a real application leaves polymorphic. Loading the decoy
 *       collections widens the hierarchy.
 *   <li><b>Branch profiles</b> go one-sided, because only one arm's outcomes ever occur. Nothing
 *       here addresses that.
 * </ul>
 *
 * <p>{@link Blackhole} is orthogonal to all of this. It stops the JIT proving a result is dead, and
 * says nothing about whether the surrounding code was compiled realistically -- a benchmark can
 * consume every result through a {@code Blackhole} and still measure a devirtualized fast path that
 * cannot occur in production.
 *
 * <p>One-sided branch profiles also interact with escape analysis in a way that can silently
 * flatter a benchmark. When a branch is never taken during profiling, C2 prunes it as an {@code
 * unstable_if} uncommon trap; if that pruned branch held the only store of an object, the object
 * becomes provably non-escaping and escape analysis scalar-replaces an allocation that production
 * would keep. The case measured in this module is {@code map.get(key)} followed by a guarded {@code
 * computeIfAbsent(key, ...)}: with every key pre-installed by {@code @Setup} the absent branch
 * never runs, so a composite key costs nothing at all. A real cache records its population-phase
 * misses in that same branch profile -- MDO counters accumulate from interpretation onward and are
 * never reset -- so the profile is two-sided, nothing is pruned, and the key is allocated on every
 * lookup. See {@code ThreadSafeMapD2Benchmark} for the measurement, and {@code
 * HashtableD2Benchmark} for the contrasting shape, where {@code merge} keeps the present/absent
 * decision inside the callee and leaves no caller-visible branch to prune.
 *
 * <p>The more complete approach is to exercise every benchmark arm during setup, across each of its
 * outcomes, so that each fork's profiles reflect the whole class rather than the one arm it is
 * about to measure. That is what restores the mix the fork removed.
 *
 * <p>Such a helper should drive the arms in a shuffled order rather than a fixed round-robin, since
 * compilation can trigger part-way through a warmup and would otherwise see whichever arm dominates
 * that point in the sequence; a regular pattern also gives the hardware branch predictor an
 * unrealistically easy time. Seeding the shuffle keeps it irregular but reproducible.
 *
 * <p>{@link #warmUpArms} is that helper, for the third gap above. An "arm" is one path through a
 * benchmark class -- ordinarily one {@code @Benchmark} method, or one outcome of a method that has
 * several (e.g. {@code getOrCreate}'s hit and miss paths). Rather than ask the author to wrap each
 * arm in a bespoke adapter, {@code arm(...)} is overloaded on the standard {@code
 * java.util.function} shapes that real benchmark methods already have, mirroring how {@code
 * TagMapFuzzTest.MapAction}'s {@code BasicAction}/{@code BasicReturningAction} adapters bridge
 * differently-shaped map operations onto one common interface -- so a state-capturing lambda like
 * {@code () -> update_hashMap(state)} or an unbound method reference like {@code
 * Foo::create_hashMap} coerces directly into one of the overloads, and overload resolution picks
 * the non-boxing primitive interface (e.g. {@code ToLongFunction}) over the boxing one ({@code
 * Function}) whenever the referenced method returns an unboxed primitive, so driving a {@code
 * long}-returning arm never boxes it.
 */
public final class BenchmarkUtils {
  private BenchmarkUtils() {}

  private static final Object[] DECOY_KEYS = {"decoy", 1, 1L, 1.0d, Boolean.TRUE, new Object()};

  /**
   * Number of pollution passes {@link #warmUpHashDispatch} runs before a trial starts. Driving
   * enough calls at {@code Level.Trial} -- entirely before JMH's warmup, let alone measurement,
   * starts -- gets HotSpot's tiered compiler through both C1 (tier 3, full profiling; default
   * invocation threshold in the hundreds) and C2 (tier 4; default thresholds in the thousands) on
   * the shared hash-dispatch call sites while decoy types are still part of their profile, so the
   * resulting compiled code keeps a genuine multi-receiver-type guard for the rest of the trial --
   * recompilation of already-C2-compiled code isn't triggered merely by one receiver type going
   * quiet, only by hitting an actual uncommon trap or similar deopt event, so it doesn't need
   * refreshing once compiled this way. This iteration count was picked by watching {@code
   * -XX:+PrintCompilation}/{@code -XX:+PrintInlining} during development and confirming both tiers
   * compile well before it's reached, with margin for ambient JVM-wide traffic on these shared JDK
   * call sites; it isn't verified at runtime (there's no portable, non-test-only JDK API for "is
   * this method at tier N").
   */
  private static final int WARM_UP_ITERATIONS = 50_000;

  /**
   * Guards {@link #warmUpHashDispatch} so the passes run once per JVM rather than once per caller.
   * Receiver profiles live in the per-method MDO and are JVM-global, so a single pass is sufficient
   * no matter how many threads or state classes ask for it. Most benchmarks here wire the call into
   * a {@code @State(Scope.Thread)} setup, which JMH runs once per thread -- without this guard,
   * {@code @Threads(8)} would perform eight times the pollution work, and the allocation it
   * generates before measurement is not free.
   */
  private static final AtomicBoolean WARMED_UP = new AtomicBoolean();

  /**
   * Exercises shared collection methods with several key classes before benchmark warmup.
   *
   * <p>HotSpot records receiver types at bytecode call sites, not per collection instance. Scratch
   * collections therefore contribute to the internal profiles used when compiling benchmark
   * lookups. Loading extra classes alone can defeat class-hierarchy analysis (optimization based on
   * the loaded implementations), but changing a receiver profile requires method calls.
   *
   * <p>{@link HashSet} uses {@link HashMap}'s lookup code, also used by {@code LinkedHashMap}.
   * {@link ConcurrentHashMap}, including its key-set views, has separate internal call sites. The
   * immutable set and map implementations selected by {@link CollectionUtils} are exercised
   * separately too; on older JDKs these helpers fall back to mutable collections.
   *
   * <p>{@link #polluteCompareToDispatch(Blackhole)} also exercises natural-order lookups in {@link
   * TreeSet}, {@link TreeMap}, and {@link ConcurrentSkipListMap}; {@link
   * #polluteComparatorDispatch(Blackhole)} exercises the same three collections' separate {@link
   * Comparator}-based call sites.
   *
   * <p>The benchmark's own collection call sites are not invoked here and remain free to specialize
   * for their receiver types. A call site's recorded type profile isn't threatened by which key
   * type happens to dominate traffic during or after warmup -- once HotSpot has recorded multiple
   * receiver types there, it stays megamorphic regardless of later call frequency. The risk this
   * class guards against is a statically deducible receiver type bypassing the profile entirely:
   * loading extra classes here defeats class-hierarchy analysis (optimization based on which
   * implementations are actually loaded), and {@link #distinctEqualCopy}'s {@code DONT_INLINE}
   * stops the JIT from tracing a decoy key's type back to its origin through static inference.
   */
  public static void warmUpHashDispatch(Blackhole bh) {
    if (!WARMED_UP.compareAndSet(false, true)) {
      return;
    }
    for (int i = 0; i < WARM_UP_ITERATIONS; ++i) {
      polluteHashDispatch(bh);
      polluteCompareToDispatch(bh);
      polluteComparatorDispatch(bh);
    }
  }

  private static void polluteHashDispatch(Blackhole bh) {
    HashSet<Object> hashSet = new HashSet<>();
    ConcurrentHashMap<Object, Object> concurrentHashMap = new ConcurrentHashMap<>();
    Map<Object, Object> mapCopySource = new HashMap<>();
    for (Object key : DECOY_KEYS) {
      hashSet.add(distinctEqualCopy(key));
      bh.consume(hashSet.contains(distinctEqualCopy(key)));

      concurrentHashMap.put(distinctEqualCopy(key), key);
      bh.consume(concurrentHashMap.get(distinctEqualCopy(key)));

      mapCopySource.put(key, key);
    }
    bh.consume(hashSet);
    bh.consume(concurrentHashMap);

    Set<Object> immutableSet = CollectionUtils.tryMakeImmutableSet(Arrays.asList(DECOY_KEYS));
    Map<Object, Object> immutableMap = CollectionUtils.tryMakeImmutableMap(mapCopySource);
    for (Object key : DECOY_KEYS) {
      bh.consume(immutableSet.contains(distinctEqualCopy(key)));
      bh.consume(immutableMap.get(distinctEqualCopy(key)));
    }
    bh.consume(immutableSet);
    bh.consume(immutableMap);
  }

  /**
   * Exercises natural-order {@code compareTo} calls in {@link TreeSet}, {@link TreeMap}, and {@link
   * ConcurrentSkipListMap} with the default decoy keys.
   *
   * <p>Each key type uses a separate collection because the decoys are not mutually comparable. The
   * instances still execute the same internal call sites and contribute to their receiver profiles.
   * {@link #polluteComparatorDispatch(Blackhole)} is the counterpart for the separate call sites
   * these collections use when constructed with an explicit {@link java.util.Comparator}.
   *
   * <p>Keys that do not implement {@link Comparable}, including the plain {@code Object} decoy, are
   * skipped.
   */
  private static void polluteCompareToDispatch(Blackhole bh) {
    for (Object key : DECOY_KEYS) {
      if (!(key instanceof Comparable)) {
        continue;
      }

      TreeSet<Object> treeSet = new TreeSet<>();
      treeSet.add(key);
      bh.consume(treeSet.contains(distinctEqualCopy(key)));
      bh.consume(treeSet);

      TreeMap<Object, Object> treeMap = new TreeMap<>();
      treeMap.put(key, key);
      bh.consume(treeMap.get(distinctEqualCopy(key)));
      bh.consume(treeMap);

      ConcurrentSkipListMap<Object, Object> skipListMap = new ConcurrentSkipListMap<>();
      skipListMap.put(key, key);
      bh.consume(skipListMap.get(distinctEqualCopy(key)));
      bh.consume(skipListMap);
    }
  }

  /**
   * Exercises {@link Comparator}-based {@code compare} calls in {@link TreeSet}, {@link TreeMap},
   * and {@link ConcurrentSkipListMap} with the default decoy keys.
   *
   * <p>Constructing these collections with an explicit {@code Comparator} routes lookups through
   * internal call sites distinct from the no-arg, natural-ordering constructors {@link
   * #polluteCompareToDispatch(Blackhole)} exercises -- pollution there does not carry over here.
   * The comparator itself delegates to {@link Comparable#compareTo}, so this pass also keeps that
   * method's call site (invoked from inside the comparator, not from the collection directly) warm
   * across the same key types.
   *
   * <p>Keys that do not implement {@link Comparable}, including the plain {@code Object} decoy, are
   * skipped.
   */
  @SuppressWarnings({"unchecked", "rawtypes"})
  private static void polluteComparatorDispatch(Blackhole bh) {
    Comparator<Object> comparator = (a, b) -> ((Comparable) a).compareTo(b);
    for (Object key : DECOY_KEYS) {
      if (!(key instanceof Comparable)) {
        continue;
      }

      TreeSet<Object> treeSet = new TreeSet<>(comparator);
      treeSet.add(key);
      bh.consume(treeSet.contains(distinctEqualCopy(key)));
      bh.consume(treeSet);

      TreeMap<Object, Object> treeMap = new TreeMap<>(comparator);
      treeMap.put(key, key);
      bh.consume(treeMap.get(distinctEqualCopy(key)));
      bh.consume(treeMap);

      ConcurrentSkipListMap<Object, Object> skipListMap = new ConcurrentSkipListMap<>(comparator);
      skipListMap.put(key, key);
      bh.consume(skipListMap.get(distinctEqualCopy(key)));
      bh.consume(skipListMap);
    }
  }

  /**
   * Returns a distinct instance that's {@code .equals()} to {@code key} but never {@code ==} it, so
   * the lookup that follows can't take {@code HashMap}/{@code ConcurrentHashMap}'s internal {@code
   * key == storedKey || key.equals(storedKey)} identity fast path and skip calling {@code equals()}
   * -- which is exactly the dispatch this class exists to pollute. {@code Object}'s own {@code
   * equals()} is identity, so a decoy of that type has no distinct-but-equal instance to make and
   * is returned as-is. The lookup then matches on identity: same result, but {@code equals()} is
   * never invoked, so the {@code Object} decoy contributes a {@code hashCode()} receiver sample
   * (the hash is computed before the identity check) and no {@code equals()} one.
   *
   * <p>{@code DONT_INLINE} makes this call boundary an optimization black box: without it, once a
   * caller like {@link #polluteHashDispatch} is inlined into a tight loop, the JIT can trace a
   * decoy key's concrete type straight back through this method to the literal it originated from
   * and devirtualize {@code hashCode()}/{@code equals()} via static type inference alone --
   * bypassing the shared, runtime type profile this class exists to pollute, no matter how many
   * distinct instances or types are pushed through it. Keeping this a real, non-inlined call forces
   * every caller to go through actual dispatch -- a type-erasing wormhole, the mirror image of
   * {@link Blackhole}'s value-erasing one.
   *
   * @param key the decoy key to copy
   * @return a distinct-but-equal copy, or {@code key} itself if it has no distinct-but-equal form
   */
  @CompilerControl(CompilerControl.Mode.DONT_INLINE)
  @SuppressWarnings({
    "deprecation",
    "removal"
  }) // boxed-type constructors: only way to force a non-cached instance
  private static Object distinctEqualCopy(Object key) {
    if (key instanceof String) {
      return new String((String) key);
    } else if (key instanceof Integer) {
      return new Integer((Integer) key);
    } else if (key instanceof Long) {
      return new Long((Long) key);
    } else if (key instanceof Double) {
      return new Double((Double) key);
    } else if (key instanceof Boolean) {
      return new Boolean((Boolean) key);
    } else {
      return key;
    }
  }

  // ---- warmUpArms: restores cross-arm profile pollution that per-method forking removes ----

  /**
   * Fixed seed for the warmup shuffle, so the order is irregular (unlike round-robin) but
   * reproducible from run to run.
   */
  private static final long ARM_SHUFFLE_SEED = 0x415233A2C0FFEEL;

  /** One no-argument arm -- see {@link #warmUpArms(Object, Blackhole, Arm0[])}. */
  public abstract static class Arm0<B> {
    abstract void invokeAndConsume(B bench, Blackhole bh);
  }

  /**
   * One single-{@code @State}-parameter arm -- see {@link #warmUpArms(Object, Object, Blackhole,
   * Arm1[])}.
   */
  public abstract static class Arm1<B, S> {
    abstract void invokeAndConsume(B bench, S state, Blackhole bh);
  }

  /**
   * One two-{@code @State}-parameter arm -- see {@link #warmUpArms(Object, Object, Object,
   * Blackhole, Arm2[])}.
   */
  public abstract static class Arm2<B, S1, S2> {
    abstract void invokeAndConsume(B bench, S1 s1, S2 s2, Blackhole bh);
  }

  /** Two-{@code @State}-parameter function, for arms shaped like {@code R method(S1 s1, S2 s2)}. */
  @FunctionalInterface
  public interface TriFunction<A, B, C, R> {
    R apply(A a, B b, C c);
  }

  /**
   * Three-argument consumer, for arms shaped like {@code void method(S state, Blackhole bh)} that
   * consume the {@link Blackhole} themselves rather than returning a value for {@code warmUpArms}
   * to consume on their behalf.
   */
  @FunctionalInterface
  public interface TriConsumer<A, B, C> {
    void accept(A a, B b, C c);
  }

  // Arm0 adapters -- an unbound reference to a no-arg @Benchmark method, or a state-capturing
  // lambda like `() -> update_hashMap(state)`, coerces directly into one of these.

  public static <B> Arm0<B> arm(Function<B, ?> fn) {
    return new Arm0<B>() {
      @Override
      void invokeAndConsume(B bench, Blackhole bh) {
        bh.consume(fn.apply(bench));
      }
    };
  }

  public static <B> Arm0<B> arm(ToLongFunction<B> fn) {
    return new Arm0<B>() {
      @Override
      void invokeAndConsume(B bench, Blackhole bh) {
        bh.consume(fn.applyAsLong(bench));
      }
    };
  }

  public static <B> Arm0<B> arm(Predicate<B> fn) {
    return new Arm0<B>() {
      @Override
      void invokeAndConsume(B bench, Blackhole bh) {
        bh.consume(fn.test(bench));
      }
    };
  }

  public static <B> Arm0<B> arm(Consumer<B> fn) {
    return new Arm0<B>() {
      @Override
      void invokeAndConsume(B bench, Blackhole bh) {
        fn.accept(bench);
      }
    };
  }

  // Arm1 adapters -- an unbound reference to a single-@State-parameter @Benchmark method coerces
  // directly into one of these (a trailing Blackhole parameter on the real method is unaffected,
  // since JMH passes that in separately from the arm's own signature).

  public static <B, S> Arm1<B, S> arm(BiFunction<B, S, ?> fn) {
    return new Arm1<B, S>() {
      @Override
      void invokeAndConsume(B bench, S state, Blackhole bh) {
        bh.consume(fn.apply(bench, state));
      }
    };
  }

  public static <B, S> Arm1<B, S> arm(ToLongBiFunction<B, S> fn) {
    return new Arm1<B, S>() {
      @Override
      void invokeAndConsume(B bench, S state, Blackhole bh) {
        bh.consume(fn.applyAsLong(bench, state));
      }
    };
  }

  public static <B, S> Arm1<B, S> arm(BiPredicate<B, S> fn) {
    return new Arm1<B, S>() {
      @Override
      void invokeAndConsume(B bench, S state, Blackhole bh) {
        bh.consume(fn.test(bench, state));
      }
    };
  }

  public static <B, S> Arm1<B, S> arm(BiConsumer<B, S> fn) {
    return new Arm1<B, S>() {
      @Override
      void invokeAndConsume(B bench, S state, Blackhole bh) {
        fn.accept(bench, state);
      }
    };
  }

  // A method reference to a void method that also takes a trailing Blackhole (e.g. `void
  // iterate_hashtable(D1State s, Blackhole bh)`) is a 3-arg unbound reference (receiver, state,
  // Blackhole), so it needs its own adapter rather than fitting BiConsumer<B, S> above.
  public static <B, S> Arm1<B, S> arm(TriConsumer<B, S, Blackhole> fn) {
    return new Arm1<B, S>() {
      @Override
      void invokeAndConsume(B bench, S state, Blackhole bh) {
        fn.accept(bench, state, bh);
      }
    };
  }

  // Arm2 adapters -- an unbound reference to a two-@State-parameter @Benchmark method (e.g.
  // getOrCreate(SharedState, ThreadState)) coerces directly into this one.

  public static <B, S1, S2> Arm2<B, S1, S2> arm(TriFunction<B, S1, S2, ?> fn) {
    return new Arm2<B, S1, S2>() {
      @Override
      void invokeAndConsume(B bench, S1 s1, S2 s2, Blackhole bh) {
        bh.consume(fn.apply(bench, s1, s2));
      }
    };
  }

  /**
   * Drives every registered no-argument arm {@link #WARM_UP_ITERATIONS} times, in a shuffled order
   * re-permuted each pass, so that by the time a {@code @Benchmark} method is measured, C2 has
   * already compiled against the whole class's mix of outcomes rather than the one path the fork is
   * about to isolate.
   *
   * <p>Coverage is declared, not inferred: register one arm per {@code @Benchmark} method, and one
   * per interesting outcome of a method that has several (e.g. {@code getOrCreate}'s hit and miss
   * paths) -- registering only the hit path is worse than not calling this at all, since it turns a
   * known gap into a silent one. {@link #warmUpArms(Object, Blackhole, Arm0[])} can only check that
   * at least as many arms were registered as there are matching {@code @Benchmark} methods; it
   * cannot tell that every outcome of a multi-outcome method was covered.
   */
  @SafeVarargs
  public static <B> void warmUpArms(B bench, Blackhole bh, Arm0<B>... arms) {
    checkArmCoverage(bench.getClass(), 0, arms.length);
    int[] order = identityOrder(arms.length);
    Random random = new Random(ARM_SHUFFLE_SEED);
    for (int pass = 0; pass < WARM_UP_ITERATIONS; ++pass) {
      shuffle(order, random);
      for (int idx : order) {
        arms[idx].invokeAndConsume(bench, bh);
      }
    }
  }

  /**
   * As {@link #warmUpArms(Object, Blackhole, Arm0[])}, for arms taking one {@code @State}
   * parameter.
   */
  @SafeVarargs
  public static <B, S> void warmUpArms(B bench, S state, Blackhole bh, Arm1<B, S>... arms) {
    checkArmCoverage(bench.getClass(), 1, arms.length);
    int[] order = identityOrder(arms.length);
    Random random = new Random(ARM_SHUFFLE_SEED);
    for (int pass = 0; pass < WARM_UP_ITERATIONS; ++pass) {
      shuffle(order, random);
      for (int idx : order) {
        arms[idx].invokeAndConsume(bench, state, bh);
      }
    }
  }

  /**
   * As {@link #warmUpArms(Object, Blackhole, Arm0[])}, for arms taking two {@code @State}
   * parameters.
   */
  @SafeVarargs
  public static <B, S1, S2> void warmUpArms(
      B bench, S1 s1, S2 s2, Blackhole bh, Arm2<B, S1, S2>... arms) {
    checkArmCoverage(bench.getClass(), 2, arms.length);
    int[] order = identityOrder(arms.length);
    Random random = new Random(ARM_SHUFFLE_SEED);
    for (int pass = 0; pass < WARM_UP_ITERATIONS; ++pass) {
      shuffle(order, random);
      for (int idx : order) {
        arms[idx].invokeAndConsume(bench, s1, s2, bh);
      }
    }
  }

  private static int[] identityOrder(int length) {
    int[] order = new int[length];
    for (int i = 0; i < length; ++i) {
      order[i] = i;
    }
    return order;
  }

  /** Fisher-Yates shuffle, in place, no per-pass allocation. */
  private static void shuffle(int[] order, Random random) {
    for (int i = order.length - 1; i > 0; --i) {
      int j = random.nextInt(i + 1);
      int tmp = order[i];
      order[i] = order[j];
      order[j] = tmp;
    }
  }

  /**
   * Coarse "did you forget to register an arm" check: counts the {@code @Benchmark} methods
   * declared on {@code benchClass} whose parameter list -- ignoring a trailing {@link Blackhole}
   * parameter, which JMH supplies independently of an arm's own state parameters -- has {@code
   * expectedStateArity} parameters, and fails loudly if fewer arms than that were registered.
   *
   * <p>This is deliberately weak: it can't tell which method is missing, and it can't tell whether
   * a multi-outcome method had every outcome driven, only that <i>some</i> arm exists for its
   * shape. Widening this to per-method, per-outcome tracking is a real follow-on once this coarse
   * form proves insufficient in practice, not before.
   */
  private static void checkArmCoverage(
      Class<?> benchClass, int expectedStateArity, int registeredArms) {
    int declaredBenchmarks = 0;
    for (Method m : benchClass.getDeclaredMethods()) {
      if (!m.isAnnotationPresent(Benchmark.class)) {
        continue;
      }
      Class<?>[] params = m.getParameterTypes();
      int arity = params.length;
      if (arity > 0 && params[arity - 1] == Blackhole.class) {
        --arity;
      }
      if (arity == expectedStateArity) {
        ++declaredBenchmarks;
      }
    }
    if (registeredArms < declaredBenchmarks) {
      throw new AssertionError(
          "warmUpArms: "
              + benchClass.getSimpleName()
              + " declares "
              + declaredBenchmarks
              + " @Benchmark method(s) with "
              + expectedStateArity
              + " state parameter(s), but only "
              + registeredArms
              + " arm(s) were registered for that shape -- coverage is declared, not inferred; see"
              + " the Arm0/Arm1/Arm2 javadoc.");
    }
  }
}
