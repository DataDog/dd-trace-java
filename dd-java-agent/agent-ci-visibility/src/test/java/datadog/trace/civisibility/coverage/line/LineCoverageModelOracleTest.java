package datadog.trace.civisibility.coverage.line;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Enumeration;
import java.util.List;
import java.util.Random;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import org.jacoco.core.analysis.Analyzer;
import org.jacoco.core.data.ExecutionData;
import org.jacoco.core.data.ExecutionDataStore;
import org.jacoco.core.internal.data.CRC64;
import org.junit.jupiter.api.Test;

/**
 * Oracle for the per-class structural coverage model ({@link ClassCoverageModel}, SDTEST-3847
 * follow-up #1).
 *
 * <p>Covered lines computed by Jacoco's real {@link Analyzer} are the source of truth. The
 * structural model (parse a class once, evaluate per-test probe arrays cheaply) must produce
 * identical results for <em>every</em> probe array. Both are monotone "OR over a fixed probe set"
 * per line, so they agree on all arrays iff their per-line probe sets are equal — which is why the
 * runtime single-array check is only a sanity guard and this offline differential test is the real
 * correctness gate. It pins the equality via:
 *
 * <ul>
 *   <li>hand-written classes covering control-flow shapes and constructs that trigger Jacoco
 *       filters (synchronized, try/finally, try-with-resources, string/enum switch, lambdas, ...),
 *       tested against random + all + none + per-probe unit arrays;
 *   <li>a corpus sweep over every class in the jacoco-core jar (hundreds of real, diverse classes)
 *       against random + all + none arrays.
 * </ul>
 */
class LineCoverageModelOracleTest {

  /** Sample with varied control flow so the reference exercises diverse Jacoco analysis. */
  @SuppressWarnings("unused")
  static final class Sample {
    int sequential(int a) {
      int x = a + 1;
      return x * 2;
    }

    int branch(int a) {
      if (a > 0) {
        return a;
      } else {
        return -a;
      }
    }

    int loop(int n) {
      int sum = 0;
      for (int i = 0; i < n; i++) {
        sum += i;
      }
      return sum;
    }

    int switchStmt(int a) {
      switch (a) {
        case 1:
          return 10;
        case 2:
          return 20;
        default:
          return 0;
      }
    }

    int tryCatch(int a) {
      try {
        return 100 / a;
      } catch (ArithmeticException e) {
        return -1;
      }
    }
  }

  /** Constructs that trigger Jacoco's filters (synchronized, try-with-resources, string switch). */
  @SuppressWarnings("unused")
  static final class FilteredSample {
    private final Lock lock = new ReentrantLock();

    int synchronizedBlock(int a) {
      lock.lock();
      try {
        return a * 2;
      } finally {
        lock.unlock();
      }
    }

    int tryWithResources(InputStream in) throws IOException {
      try (InputStream stream = in) {
        return stream.read();
      }
    }

    int stringSwitch(String s) {
      switch (s) {
        case "a":
          return 1;
        case "b":
          return 2;
        default:
          return 0;
      }
    }

    int ternaryAndBoolean(int a, int b) {
      boolean r = a > 0 && b > 0 || a == b;
      return r ? a : b;
    }

    Runnable lambda(int a) {
      return () -> {
        int x = a + 1;
        System.out.println(x);
      };
    }
  }

  /** Further constructs with special Jacoco flow/filter handling. */
  @SuppressWarnings("unused")
  static final class MoreConstructs {
    enum Color {
      RED,
      GREEN,
      BLUE
    }

    int enumSwitch(Color c) {
      switch (c) {
        case RED:
          return 1;
        case GREEN:
          return 2;
        default:
          return 3;
      }
    }

    int finallyWithReturn(int a) {
      try {
        if (a < 0) {
          return -1;
        }
        return a;
      } finally {
        System.out.println("done");
      }
    }

    int nestedTryCatch(int a) {
      try {
        try {
          return 10 / a;
        } catch (ArithmeticException e) {
          return -1;
        }
      } finally {
        System.out.println("outer");
      }
    }

    int multiCatch(String s) {
      try {
        return Integer.parseInt(s) + s.charAt(5);
      } catch (NumberFormatException | IndexOutOfBoundsException e) {
        return -1;
      }
    }

    int tryWithMultipleResources(InputStream a, InputStream b) throws IOException {
      try (InputStream x = a;
          InputStream y = b) {
        return x.read() + y.read();
      }
    }

    int labeledBreak(int[][] grid, int target) {
      int found = -1;
      outer:
      for (int i = 0; i < grid.length; i++) {
        for (int j = 0; j < grid[i].length; j++) {
          if (grid[i][j] == target) {
            found = i;
            break outer;
          }
        }
      }
      return found;
    }

    int withAssert(int a) {
      assert a >= 0 : "negative";
      return a * 2;
    }

    int enhancedFor(List<Integer> items) {
      int sum = 0;
      for (int v : items) {
        sum += v;
      }
      return sum;
    }

    Runnable nestedCapturingLambda(int a) {
      return () -> {
        Runnable inner =
            () -> {
              int x = a + 1;
              System.out.println(x);
            };
        inner.run();
      };
    }

    synchronized int synchronizedMethod(int a) {
      return a + 1;
    }
  }

  // Generously oversized: Jacoco's analysis reads probes[id] for id < the class' real probe count
  // and ignores the rest, so the exact count is not needed for the reference computation.
  private static final int PROBES = 1024;

  private static final List<Class<?>> SAMPLES = new ArrayList<>();

  static {
    SAMPLES.add(Sample.class);
    SAMPLES.add(FilteredSample.class);
    SAMPLES.add(MoreConstructs.class);
  }

  /** Reference covered-lines for class bytes given a probe array, via Jacoco's {@link Analyzer}. */
  static BitSet referenceCoveredLines(byte[] bytes, String vmName, boolean[] probes)
      throws Exception {
    long classId = CRC64.classId(bytes);
    ExecutionDataStore store = new ExecutionDataStore();
    store.put(new ExecutionData(classId, vmName, probes.clone()));
    BitSet coveredLines = new BitSet();
    new Analyzer(store, new SourceAnalyzer(coveredLines)).analyzeClass(bytes, vmName);
    return coveredLines;
  }

  static BitSet referenceCoveredLines(Class<?> clazz, boolean[] probes) throws Exception {
    return referenceCoveredLines(readBytecode(clazz), clazz.getName().replace('.', '/'), probes);
  }

  private static byte[] readBytecode(Class<?> clazz) throws Exception {
    try (InputStream is =
        clazz.getResourceAsStream("/" + clazz.getName().replace('.', '/') + ".class")) {
      return readAll(is);
    }
  }

  private static byte[] readAll(InputStream is) throws IOException {
    ByteArrayOutputStream bos = new ByteArrayOutputStream();
    byte[] buf = new byte[8192];
    int n;
    while ((n = is.read(buf)) > 0) {
      bos.write(buf, 0, n);
    }
    return bos.toByteArray();
  }

  private static boolean[] randomProbes(Random random) {
    boolean[] p = new boolean[PROBES];
    for (int i = 0; i < p.length; i++) {
      p[i] = random.nextBoolean();
    }
    return p;
  }

  private static boolean[] filled(boolean value) {
    boolean[] p = new boolean[PROBES];
    java.util.Arrays.fill(p, value);
    return p;
  }

  @Test
  void modelMatchesJacocoForAllProbeArrays() throws Exception {
    Random random = new Random(123);
    for (Class<?> clazz : SAMPLES) {
      byte[] bytes = readBytecode(clazz);
      String vmName = clazz.getName().replace('.', '/');
      ClassCoverageModel model = ClassCoverageModel.build(bytes);

      assertEquals(
          referenceCoveredLines(bytes, vmName, filled(false)),
          model.coveredLines(filled(false)),
          () -> clazz.getName() + " (no probes)");
      assertEquals(
          referenceCoveredLines(bytes, vmName, filled(true)),
          model.coveredLines(filled(true)),
          () -> clazz.getName() + " (all probes)");

      for (int trial = 0; trial < 200; trial++) {
        boolean[] probes = randomProbes(random);
        assertEquals(
            referenceCoveredLines(bytes, vmName, probes),
            model.coveredLines(probes),
            () -> clazz.getName() + " (random probes)");
      }
    }
  }

  @Test
  void modelMatchesJacocoForUnitProbeArrays() throws Exception {
    // Per-probe (unit) arrays pin the exact per-line probe-set membership, not just line presence.
    for (Class<?> clazz : SAMPLES) {
      byte[] bytes = readBytecode(clazz);
      String vmName = clazz.getName().replace('.', '/');
      ClassCoverageModel model = ClassCoverageModel.build(bytes);
      for (int i = 0; i < 128; i++) {
        boolean[] unit = new boolean[PROBES];
        unit[i] = true;
        final int probeId = i;
        assertEquals(
            referenceCoveredLines(bytes, vmName, unit),
            model.coveredLines(unit),
            () -> clazz.getName() + " (unit probe " + probeId + ")");
      }
    }
  }

  @Test
  void modelMatchesJacocoAcrossJacocoCoreJar() throws Exception {
    File jar = jarContaining(Analyzer.class);
    org.junit.jupiter.api.Assumptions.assumeTrue(
        jar != null && jar.isFile(), "jacoco-core is not a jar on the classpath");

    Random random = new Random(2027);
    int tested = 0;
    int skipped = 0;
    try (JarFile jarFile = new JarFile(jar)) {
      Enumeration<JarEntry> entries = jarFile.entries();
      while (entries.hasMoreElements()) {
        JarEntry entry = entries.nextElement();
        String name = entry.getName();
        if (!name.endsWith(".class")
            || name.endsWith("module-info.class")
            || name.endsWith("package-info.class")) {
          continue;
        }
        byte[] bytes;
        try (InputStream is = jarFile.getInputStream(entry)) {
          bytes = readAll(is);
        }
        String vmName = name.substring(0, name.length() - ".class".length());

        ClassCoverageModel model;
        BitSet refAll;
        try {
          model = ClassCoverageModel.build(bytes);
          // all-true both anchors the coverable-line universe and confirms the class is analyzable
          refAll = referenceCoveredLines(bytes, vmName, filled(true));
        } catch (Exception e) {
          // classes Jacoco or the model cannot process are handled by runtime fallback; skip here
          skipped++;
          continue;
        }

        assertEquals(refAll, model.coveredLines(filled(true)), () -> vmName + " (all probes)");
        assertEquals(
            referenceCoveredLines(bytes, vmName, filled(false)),
            model.coveredLines(filled(false)),
            () -> vmName + " (no probes)");
        for (int trial = 0; trial < 8; trial++) {
          boolean[] probes = randomProbes(random);
          assertEquals(
              referenceCoveredLines(bytes, vmName, probes),
              model.coveredLines(probes),
              () -> vmName + " (random probes)");
        }
        tested++;
      }
    }
    assertTrue(tested > 50, "expected to differential-test many real classes, got " + tested);
  }

  @Test
  void allProbesCoverLines() throws Exception {
    assertFalse(
        referenceCoveredLines(Sample.class, filled(true)).isEmpty(),
        "a class with executable code should report covered lines when all probes fire");
  }

  @Test
  void coverageIsMonotonicInProbeSet() throws Exception {
    Random random = new Random(42);
    for (int trial = 0; trial < 50; trial++) {
      boolean[] subset = randomProbes(random);
      boolean[] superset = subset.clone();
      for (int i = 0; i < superset.length; i++) {
        superset[i] |= random.nextBoolean();
      }
      BitSet subCovered = referenceCoveredLines(Sample.class, subset);
      BitSet superCovered = referenceCoveredLines(Sample.class, superset);

      BitSet intersection = (BitSet) subCovered.clone();
      intersection.and(superCovered);
      assertEquals(
          subCovered,
          intersection,
          "lines covered by a probe subset must remain covered by a superset");
    }
  }

  private static File jarContaining(Class<?> clazz) {
    try {
      return new File(clazz.getProtectionDomain().getCodeSource().getLocation().toURI());
    } catch (Exception e) {
      return null;
    }
  }
}
