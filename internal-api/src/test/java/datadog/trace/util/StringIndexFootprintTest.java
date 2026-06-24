package datadog.trace.util;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.openjdk.jol.info.GraphLayout;

/**
 * Retained-footprint comparison (JOL) for {@link StringIndex} vs the JDK set representations, over
 * a fixed read-only string set. Footprint is deterministic, so this is safe to run under load
 * (unlike the throughput benchmarks).
 *
 * <p>All structures hold the <i>same</i> String instances, so the shared strings cancel out and the
 * differences reflect structural overhead. We report total retained bytes and the overhead above a
 * plain {@code String[]} (which is just the strings + a reference array). {@code Set.copyOf} yields
 * the JDK's compact {@code SetN} only on Java 10+ (it falls back to {@code HashSet} pre-10), so the
 * copyOf row is only meaningful on a 10+ test JVM.
 *
 * <p>The one robust cross-JVM invariant we assert is that {@code StringIndex} is lighter than
 * {@code HashSet} (no per-element {@code Node} objects). The {@code StringIndex} vs {@code SetN}
 * comparison is left as reported data rather than an assertion: {@code StringIndex} caches an
 * {@code int[]} of hashes that {@code SetN} does not, so which one wins on bytes is genuinely worth
 * measuring.
 *
 * <p>Measured retained bytes (Java 17, JOL estimate mode — relative ordering reliable, exact bytes
 * approximate):
 *
 * <pre>{@code
 * n      array   hashSet  treeSet   copyOf  stringIndex
 * 8        496      864      848      552      760
 * 32      1936     3168     3152     2088     2872
 * 128     7696    12384    12368     8232    11320
 * }</pre>
 *
 * Finding: {@code StringIndex} is ~9% lighter than {@code HashSet}/{@code TreeSet} (no per-element
 * {@code Node} objects), but {@code Set.copyOf} ({@code SetN}) is the most compact by a wide margin
 * (~27% under {@code StringIndex} at n=128) — {@code StringIndex} pays for its cached {@code int[]}
 * hashes and 2x-oversized {@code String[]}. So {@code StringIndex}'s edge over {@code SetN} is
 * speed and the {@code indexOf}-&gt;parallel-array capability, not footprint.
 */
class StringIndexFootprintTest {

  static String[] elements(int n) {
    String[] a = new String[n];
    for (int i = 0; i < n; ++i) {
      a[i] = "element-key-" + i;
    }
    return a;
  }

  static long bytes(Object root) {
    return GraphLayout.parseInstance(root).totalSize();
  }

  @Test
  void footprintComparison() {
    System.out.printf(
        "%-6s %12s %12s %12s %12s %12s%n",
        "n", "array", "hashSet", "treeSet", "copyOf", "stringIndex");
    System.out.printf(
        "%-6s %12s %12s %12s %12s %12s   (overhead above array)%n", "", "", "", "", "", "");

    for (int n : new int[] {8, 32, 128}) {
      String[] el = elements(n);

      long array = bytes((Object) el); // baseline: strings + reference array
      long hashSet = bytes(new HashSet<>(Arrays.asList(el)));
      long treeSet = bytes(new TreeSet<>(Arrays.asList(el)));
      Set<String> copy = CollectionUtils.tryMakeImmutableSet(Arrays.asList(el));
      long copyOf = bytes(copy);
      long stringIndex = bytes(StringIndex.of(el));

      System.out.printf(
          "%-6d %12d %12d %12d %12d %12d%n", n, array, hashSet, treeSet, copyOf, stringIndex);
      System.out.printf(
          "%-6s %12s %12d %12d %12d %12d%n",
          "", "", hashSet - array, treeSet - array, copyOf - array, stringIndex - array);

      // Robust cross-JVM invariant: no per-element Node objects -> lighter than HashSet.
      assertTrue(
          stringIndex < hashSet, "StringIndex should retain fewer bytes than HashSet at n=" + n);
    }
  }
}
