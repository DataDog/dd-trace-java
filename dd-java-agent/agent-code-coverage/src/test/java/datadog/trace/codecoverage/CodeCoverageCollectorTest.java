package datadog.trace.codecoverage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.coverage.CoverageKey;
import datadog.trace.coverage.LinesCoverage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.jacoco.core.data.ExecutionData;
import org.jacoco.core.internal.data.CRC64;
import org.jacoco.core.internal.flow.ClassProbesAdapter;
import org.jacoco.core.internal.flow.ClassProbesVisitor;
import org.jacoco.core.internal.flow.IFrame;
import org.jacoco.core.internal.flow.MethodProbesVisitor;
import org.jacoco.core.internal.instr.InstrSupport;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Label;

class CodeCoverageCollectorTest {

  // ===== Sample classes used as test subjects =====

  @SuppressWarnings("unused")
  static class SampleClassA {
    int compute(int x) {
      int a = x + 1;
      int b = a * 2;
      return b;
    }
  }

  @SuppressWarnings("unused")
  static class SampleClassB {
    int compute(int x) {
      if (x > 0) {
        return x;
      }
      return -x;
    }
  }

  // ===== Tests =====

  @Test
  void classWithHits_producesNormalCoverageRecord() {
    byte[] classBytes = bytecodeFor(SampleClassA.class);
    long classId = CRC64.classId(classBytes);
    String className = SampleClassA.class.getName().replace('.', '/');
    int probeCount = countProbes(classBytes);

    boolean[] probes = new boolean[probeCount];
    Arrays.fill(probes, true);

    List<Map<CoverageKey, LinesCoverage>> uploads = new ArrayList<>();

    CodeCoverageCollector collector =
        new CodeCoverageCollector(
            (store, session) -> store.put(new ExecutionData(classId, className, probes)),
            Collections::emptyList,
            uploads::add,
            60,
            null);

    collector.collect();

    assertEquals(1, uploads.size());
    Map<CoverageKey, LinesCoverage> coverage = uploads.get(0);
    assertEquals(1, coverage.size());

    LinesCoverage lc = coverage.values().iterator().next();
    assertFalse(lc.executableLines.isEmpty(), "should have executable lines");
    assertFalse(lc.coveredLines.isEmpty(), "should have covered lines");
  }

  @Test
  void newClassWithoutHits_reportsExecutableLinesOnly() {
    String className = SampleClassB.class.getName().replace('.', '/');

    List<Map<CoverageKey, LinesCoverage>> uploads = new ArrayList<>();

    CodeCoverageCollector collector =
        new CodeCoverageCollector(
            (store, session) -> {},
            () -> Collections.singletonList(className),
            uploads::add,
            60,
            null);

    collector.collect();

    assertEquals(1, uploads.size());
    Map<CoverageKey, LinesCoverage> coverage = uploads.get(0);
    assertEquals(1, coverage.size());

    LinesCoverage lc = coverage.values().iterator().next();
    assertFalse(lc.executableLines.isEmpty(), "should have executable lines");
    assertTrue(lc.coveredLines.isEmpty(), "should have no covered lines");
  }

  @Test
  void newClassWithHits_notDuplicated() {
    byte[] classBytes = bytecodeFor(SampleClassA.class);
    long classId = CRC64.classId(classBytes);
    String className = SampleClassA.class.getName().replace('.', '/');
    int probeCount = countProbes(classBytes);

    boolean[] probes = new boolean[probeCount];
    probes[0] = true;

    List<Map<CoverageKey, LinesCoverage>> uploads = new ArrayList<>();

    CodeCoverageCollector collector =
        new CodeCoverageCollector(
            (store, session) -> store.put(new ExecutionData(classId, className, probes)),
            () -> Collections.singletonList(className),
            uploads::add,
            60,
            null);

    collector.collect();

    assertEquals(1, uploads.size());
    Map<CoverageKey, LinesCoverage> coverage = uploads.get(0);
    assertEquals(1, coverage.size(), "class with hits and in drain should produce one entry");
  }

  @Test
  void noHitsNoDrain_nothingUploaded() {
    List<Map<CoverageKey, LinesCoverage>> uploads = new ArrayList<>();

    CodeCoverageCollector collector =
        new CodeCoverageCollector(
            (store, session) -> {}, Collections::emptyList, uploads::add, 60, null);

    collector.collect();

    assertTrue(uploads.isEmpty(), "nothing should be uploaded");
  }

  @Test
  void drainedClassNotReReported() {
    String className = SampleClassB.class.getName().replace('.', '/');
    AtomicInteger drainCallCount = new AtomicInteger(0);

    List<Map<CoverageKey, LinesCoverage>> uploads = new ArrayList<>();

    CodeCoverageCollector collector =
        new CodeCoverageCollector(
            (store, session) -> {},
            () -> {
              if (drainCallCount.getAndIncrement() == 0) {
                return Collections.singletonList(className);
              }
              return Collections.emptyList();
            },
            uploads::add,
            60,
            null);

    // First cycle: should report the class
    collector.collect();
    assertEquals(1, uploads.size());

    // Second cycle: drain returns empty, no hits -> nothing to upload
    collector.collect();
    assertEquals(1, uploads.size(), "should not upload again when no new data");
  }

  @Test
  void mixedHitAndNoHitClasses() {
    byte[] hitClassBytes = bytecodeFor(SampleClassA.class);
    long hitClassId = CRC64.classId(hitClassBytes);
    String hitClassName = SampleClassA.class.getName().replace('.', '/');
    int hitProbeCount = countProbes(hitClassBytes);

    String noHitClassName = SampleClassB.class.getName().replace('.', '/');

    boolean[] probes = new boolean[hitProbeCount];
    Arrays.fill(probes, true);

    List<Map<CoverageKey, LinesCoverage>> uploads = new ArrayList<>();

    CodeCoverageCollector collector =
        new CodeCoverageCollector(
            (store, session) -> store.put(new ExecutionData(hitClassId, hitClassName, probes)),
            () -> Arrays.asList(hitClassName, noHitClassName),
            uploads::add,
            60,
            null);

    collector.collect();

    assertEquals(1, uploads.size());
    Map<CoverageKey, LinesCoverage> coverage = uploads.get(0);
    assertEquals(2, coverage.size(), "should have entries for both classes");

    LinesCoverage hitLc = null;
    LinesCoverage noHitLc = null;
    for (Map.Entry<CoverageKey, LinesCoverage> entry : coverage.entrySet()) {
      if (entry.getKey().className.equals(hitClassName)) {
        hitLc = entry.getValue();
      } else if (entry.getKey().className.equals(noHitClassName)) {
        noHitLc = entry.getValue();
      }
    }

    assertNotNull(hitLc, "hit class should be in coverage");
    assertFalse(hitLc.coveredLines.isEmpty(), "hit class should have covered lines");
    assertFalse(hitLc.executableLines.isEmpty(), "hit class should have executable lines");

    assertNotNull(noHitLc, "no-hit class should be in coverage");
    assertTrue(noHitLc.coveredLines.isEmpty(), "no-hit class should have no covered lines");
    assertFalse(noHitLc.executableLines.isEmpty(), "no-hit class should have executable lines");
  }

  @Test
  void newClassExecutableLines_matchFullBuild() {
    // Verify that buildBaseline produces the same executable lines as the full build
    byte[] classBytes = bytecodeFor(SampleClassB.class);
    long classId = CRC64.classId(classBytes);
    String className = SampleClassB.class.getName().replace('.', '/');
    int probeCount = countProbes(classBytes);

    ClassProbeMapping full =
        ClassProbeMappingBuilder.build(classId, className, probeCount, classBytes);
    ClassProbeMapping baseline =
        ClassProbeMappingBuilder.buildBaseline(classId, className, classBytes);

    assertNotNull(baseline);
    assertEquals(full.executableLines, baseline.executableLines);
    assertEquals(full.sourceFile, baseline.sourceFile);
    assertEquals(0, baseline.probeToLines.length, "baseline should have no probe-to-lines mapping");
  }

  // ===== Helpers =====

  private static byte[] bytecodeFor(Class<?> clazz) {
    String resource = clazz.getName().replace('.', '/') + ".class";
    try (InputStream is = clazz.getClassLoader().getResourceAsStream(resource)) {
      assertNotNull(is, "Could not find class file for " + clazz.getName());
      return readAllBytes(is);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  private static int countProbes(byte[] classBytes) {
    int[] count = {0};
    ClassProbesAdapter adapter =
        new ClassProbesAdapter(
            new ClassProbesVisitor() {
              @Override
              public MethodProbesVisitor visitMethod(
                  int access, String name, String desc, String signature, String[] exceptions) {
                return new MethodProbesVisitor() {
                  @Override
                  public void visitProbe(int probeId) {}

                  @Override
                  public void visitJumpInsnWithProbe(
                      int opcode, Label label, int probeId, IFrame frame) {}

                  @Override
                  public void visitInsnWithProbe(int opcode, int probeId) {}

                  @Override
                  public void visitTableSwitchInsnWithProbes(
                      int min, int max, Label dflt, Label[] labels, IFrame frame) {}

                  @Override
                  public void visitLookupSwitchInsnWithProbes(
                      Label dflt, int[] keys, Label[] labels, IFrame frame) {}
                };
              }

              @Override
              public void visitTotalProbeCount(int c) {
                count[0] = c;
              }
            },
            false);
    InstrSupport.classReaderFor(classBytes).accept(adapter, 0);
    return count[0];
  }

  private static byte[] readAllBytes(InputStream is) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    byte[] buf = new byte[4096];
    int r;
    while ((r = is.read(buf)) != -1) {
      out.write(buf, 0, r);
    }
    return out.toByteArray();
  }
}
