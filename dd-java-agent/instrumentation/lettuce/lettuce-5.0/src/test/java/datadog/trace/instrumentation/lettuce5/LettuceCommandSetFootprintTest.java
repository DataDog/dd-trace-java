package datadog.trace.instrumentation.lettuce5;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import datadog.environment.JavaVirtualMachine;
import datadog.trace.util.StringIndex;
import java.util.Arrays;
import java.util.HashSet;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.openjdk.jol.info.GraphLayout;

/**
 * Retained-footprint comparison (JOL) of the two command-name sets in {@link
 * LettuceInstrumentationUtil} across three structures: {@code HashSet} (in use today), a {@link
 * StringIndex} instance, and {@link StringIndex.EmbeddingSupport} arrays.
 *
 * <p>Footprint is deterministic, so unlike a throughput benchmark this is safe to run under load.
 *
 * <p>Measured at the declared sizes, 4 and 6 entries. {@code capacityFor} rounds to a power of two,
 * putting those on 8- and 16-slot tables at the default 0.5 load factor, while {@code new
 * HashSet<>(Collection)} floors its table at 16 slots for both.
 *
 * <p>All candidates hold the same interned {@code String} instances, so the strings cancel out and
 * the comparable figure is overhead above a plain {@code String[]} of the same names.
 *
 * <p>Both sets are {@code static final}, built once per classloader the helper is injected into
 * ({@code HelperInjector} keys injection by classloader), not per command. Per-lookup allocation is
 * zero for all three structures; see {@code LettuceCommandClassificationBenchmark} under {@code
 * -Pjmh.profilers=gc}.
 *
 * <p>Measured retained bytes (JDK 17.0.18, JOL estimate mode: ordering reliable, exact bytes
 * approximate). The combined row measures both structures as simultaneous roots, since the two sets
 * share the interned {@code "DEBUG"} instance and summing separate measurements would count it
 * twice:
 *
 * <pre>{@code
 * set                        n    array   hashSet  siInstance  siEmbedded
 * nonInstrumentingCommands   4      224       480         312         288
 *   overhead above array                     (256)        (88)        (64)
 * agentCrashingCommands      6      328       640         472         448
 *   overhead above array                     (312)       (144)       (120)
 * TOTAL both sets                           1120         784         736
 *   saving vs hashSet                                     336         384
 * }</pre>
 *
 * <p>Both {@code StringIndex} modes undercut {@code HashSet}: structural overhead is 568 B against
 * 232 B for the instance and 184 B for the embedded arrays.
 */
class LettuceCommandSetFootprintTest {

  @BeforeAll
  static void assumeNotJ9Jvm() {
    // JOL's GraphLayout relies on HotSpot-specific Unsafe internals and throws on J9-based JVMs
    // (IBM/Semeru) -- same guard as StringIndexFootprintTest.
    assumeFalse(JavaVirtualMachine.isJ9());
  }

  static long bytes(final Object... roots) {
    return GraphLayout.parseInstance(roots).totalSize();
  }

  @Test
  void footprintComparison() {
    final String[] nonInstrumenting = LettuceInstrumentationUtil.NON_INSTRUMENTING_COMMAND_WORDS;
    final String[] agentCrashing = LettuceInstrumentationUtil.AGENT_CRASHING_COMMANDS_WORDS;

    System.out.printf(
        "%-28s %6s %10s %10s %10s %10s%n",
        "set", "n", "array", "hashSet", "siInstance", "siEmbedded");

    for (final String[] words : new String[][] {nonInstrumenting, agentCrashing}) {
      final String name =
          words == nonInstrumenting ? "nonInstrumentingCommands" : "agentCrashingCommands";

      final long array = bytes((Object) words);
      final long hashSet = bytes(new HashSet<>(Arrays.asList(words)));
      final long siInstance = bytes(StringIndex.of(words));
      final StringIndex.Data data = StringIndex.EmbeddingSupport.create(words);
      final long siEmbedded = bytes(data.hashes, data.names);

      System.out.printf(
          "%-28s %6d %10d %10d %10d %10d%n",
          name, words.length, array, hashSet, siInstance, siEmbedded);
      System.out.printf(
          "%-28s %6s %10s %10d %10d %10d   (overhead above array)%n",
          "", "", "", hashSet - array, siInstance - array, siEmbedded - array);

      assertTrue(
          siInstance < hashSet,
          "StringIndex instance should retain fewer bytes than HashSet for " + name);
      assertTrue(
          siEmbedded < hashSet,
          "StringIndex embedded arrays should retain fewer bytes than HashSet for " + name);
    }

    // Both structures are measured as simultaneous roots. The two sets share the interned "DEBUG"
    // instance, so measuring them separately and adding the results counts that object twice.
    final long bothArrays = bytes(nonInstrumenting, agentCrashing);
    final long bothHashSets =
        bytes(
            new HashSet<>(Arrays.asList(nonInstrumenting)),
            new HashSet<>(Arrays.asList(agentCrashing)));
    final long bothInstances =
        bytes(StringIndex.of(nonInstrumenting), StringIndex.of(agentCrashing));
    final StringIndex.Data niData = StringIndex.EmbeddingSupport.create(nonInstrumenting);
    final StringIndex.Data acData = StringIndex.EmbeddingSupport.create(agentCrashing);
    final long bothEmbedded = bytes(niData.hashes, niData.names, acData.hashes, acData.names);

    System.out.printf(
        "%n%-28s %6s %10d %10d %10d %10d%n",
        "BOTH SETS (combined graph)", "", bothArrays, bothHashSets, bothInstances, bothEmbedded);
    System.out.printf(
        "%-28s %6s %10s %10s %10d %10d   (saving vs hashSet, per classloader)%n",
        "", "", "", "", bothHashSets - bothInstances, bothHashSets - bothEmbedded);
  }
}
