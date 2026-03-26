package datadog.trace.codecoverage;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.BitSet;
import org.jacoco.core.analysis.Analyzer;
import org.jacoco.core.analysis.CoverageBuilder;
import org.jacoco.core.analysis.IClassCoverage;
import org.jacoco.core.analysis.ISourceNode;
import org.jacoco.core.data.ExecutionData;
import org.jacoco.core.data.ExecutionDataStore;
import org.jacoco.core.internal.data.CRC64;
import org.jacoco.core.internal.flow.ClassProbesAdapter;
import org.jacoco.core.internal.flow.ClassProbesVisitor;
import org.jacoco.core.internal.flow.IFrame;
import org.jacoco.core.internal.flow.MethodProbesVisitor;
import org.jacoco.core.internal.instr.InstrSupport;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Label;

class ClassProbeMappingBuilderTest {

  // ===== Sample classes exercising different bytecode patterns =====

  /** Linear code with no branches. */
  @SuppressWarnings("unused")
  static class SimpleLinear {
    int compute(int x) {
      int a = x + 1;
      int b = a * 2;
      return b;
    }
  }

  /** If/else branch. */
  @SuppressWarnings("unused")
  static class IfElseBranch {
    int abs(int x) {
      if (x < 0) {
        return -x;
      } else {
        return x;
      }
    }
  }

  /** Multiple sequential conditions — tests fall-through after probed conditional jumps. */
  @SuppressWarnings("unused")
  static class MultipleConditions {
    int classify(int x) {
      int result = 0;
      if (x > 0) {
        result = 1;
      }
      if (x > 10) {
        result = 2;
      }
      return result;
    }
  }

  /** Conditional with fall-through: exercises the visitJumpInsnWithProbe bug. */
  @SuppressWarnings("unused")
  static class ConditionalFallThrough {
    int compute(int x) {
      int a = 1;
      if (x > 0) {
        a = 2;
      }
      int b = a + 1;
      return b;
    }
  }

  /** Dense switch statement (compiled as tableswitch). */
  @SuppressWarnings("unused")
  static class TableSwitch {
    int compute(int x) {
      switch (x) {
        case 0:
          return 10;
        case 1:
          return 20;
        case 2:
          return 30;
        case 3:
          return 40;
        default:
          return -1;
      }
    }
  }

  /** Sparse switch statement (compiled as lookupswitch). */
  @SuppressWarnings("unused")
  static class LookupSwitch {
    int compute(int x) {
      switch (x) {
        case 100:
          return 1;
        case 200:
          return 2;
        case 300:
          return 3;
        default:
          return 0;
      }
    }
  }

  /** Switch with shared targets — exercises visitSwitchInsnWithProbes. */
  @SuppressWarnings("unused")
  static class SwitchWithSharedTargets {
    String describe(int x) {
      switch (x) {
        case 1:
        case 2:
        case 3:
          return "small";
        case 4:
        case 5:
        case 6:
          return "medium";
        default:
          return "other";
      }
    }
  }

  /** Try-catch block. */
  @SuppressWarnings("unused")
  static class TryCatch {
    int safeDivide(int a, int b) {
      try {
        return a / b;
      } catch (ArithmeticException e) {
        return 0;
      }
    }
  }

  /** Try-catch-finally block. */
  @SuppressWarnings("unused")
  static class TryCatchFinally {
    int compute(int x) {
      int result = 0;
      try {
        result = 100 / x;
      } catch (ArithmeticException e) {
        result = -1;
      } finally {
        result += 1;
      }
      return result;
    }
  }

  /** For loop. */
  @SuppressWarnings("unused")
  static class ForLoop {
    int sum(int n) {
      int total = 0;
      for (int i = 0; i < n; i++) {
        total += i;
      }
      return total;
    }
  }

  /** While loop. */
  @SuppressWarnings("unused")
  static class WhileLoop {
    int countDown(int n) {
      int count = 0;
      while (n > 0) {
        count++;
        n--;
      }
      return count;
    }
  }

  /** Nested conditions. */
  @SuppressWarnings("unused")
  static class NestedConditions {
    String classify(int x, int y) {
      if (x > 0) {
        if (y > 0) {
          return "both positive";
        } else {
          return "x positive, y not";
        }
      } else {
        return "x not positive";
      }
    }
  }

  /** Class with multiple methods. */
  @SuppressWarnings("unused")
  static class MultipleMethods {
    int add(int a, int b) {
      return a + b;
    }

    int multiply(int a, int b) {
      return a * b;
    }

    int negate(int a) {
      return -a;
    }
  }

  /** Empty void method. */
  @SuppressWarnings("unused")
  static class EmptyMethod {
    void doNothing() {}
  }

  /** Ternary expression (inline conditional). */
  @SuppressWarnings("unused")
  static class TernaryExpression {
    int max(int a, int b) {
      return a > b ? a : b;
    }
  }

  /** Boolean short-circuit expressions. */
  @SuppressWarnings("unused")
  static class ShortCircuit {
    boolean check(int x, int y) {
      return x > 0 && y > 0;
    }
  }

  /** Method with early return. */
  @SuppressWarnings("unused")
  static class EarlyReturn {
    int compute(int x) {
      if (x == 0) {
        return -1;
      }
      int a = x * 2;
      int b = a + 1;
      return b;
    }
  }

  /** Interface with no methods (zero probes). */
  @SuppressWarnings("unused")
  interface EmptyInterface {}

  // ===== Tests: builder output matches direct JaCoCo analysis =====

  @Test
  void simpleLinear() throws Exception {
    assertMatchesJaCoCo(SimpleLinear.class);
  }

  @Test
  void ifElseBranch() throws Exception {
    assertMatchesJaCoCo(IfElseBranch.class);
  }

  @Test
  void multipleConditions() throws Exception {
    assertMatchesJaCoCo(MultipleConditions.class);
  }

  @Test
  void conditionalFallThrough() throws Exception {
    assertMatchesJaCoCo(ConditionalFallThrough.class);
  }

  @Test
  void tableSwitch() throws Exception {
    assertMatchesJaCoCo(TableSwitch.class);
  }

  @Test
  void lookupSwitch() throws Exception {
    assertMatchesJaCoCo(LookupSwitch.class);
  }

  @Test
  void switchWithSharedTargets() throws Exception {
    assertMatchesJaCoCo(SwitchWithSharedTargets.class);
  }

  @Test
  void tryCatch() throws Exception {
    assertMatchesJaCoCo(TryCatch.class);
  }

  @Test
  void tryCatchFinally() throws Exception {
    assertMatchesJaCoCo(TryCatchFinally.class);
  }

  @Test
  void forLoop() throws Exception {
    assertMatchesJaCoCo(ForLoop.class);
  }

  @Test
  void whileLoop() throws Exception {
    assertMatchesJaCoCo(WhileLoop.class);
  }

  @Test
  void nestedConditions() throws Exception {
    assertMatchesJaCoCo(NestedConditions.class);
  }

  @Test
  void multipleMethods() throws Exception {
    assertMatchesJaCoCo(MultipleMethods.class);
  }

  @Test
  void emptyMethod() throws Exception {
    assertMatchesJaCoCo(EmptyMethod.class);
  }

  @Test
  void ternaryExpression() throws Exception {
    assertMatchesJaCoCo(TernaryExpression.class);
  }

  @Test
  void shortCircuit() throws Exception {
    assertMatchesJaCoCo(ShortCircuit.class);
  }

  @Test
  void earlyReturn() throws Exception {
    assertMatchesJaCoCo(EarlyReturn.class);
  }

  // ===== Tests: structural properties across all sample classes =====

  @Test
  void probeLines_areSubsetOfExecutableLines() throws Exception {
    for (Class<?> clazz : allSampleClasses()) {
      ClassProbeMapping mapping = buildMapping(clazz);
      int probeCount = mapping.probeToLines.length;
      for (int p = 0; p < probeCount; p++) {
        for (int line : mapping.probeToLines[p]) {
          assertTrue(
              mapping.executableLines.get(line),
              clazz.getSimpleName()
                  + ": probe "
                  + p
                  + " covers line "
                  + line
                  + " which is not executable");
        }
      }
    }
  }

  @Test
  void allExecutableLines_areCoveredBySomeProbe() throws Exception {
    for (Class<?> clazz : allSampleClasses()) {
      ClassProbeMapping mapping = buildMapping(clazz);
      BitSet covered = new BitSet();
      for (int[] lines : mapping.probeToLines) {
        for (int line : lines) {
          covered.set(line);
        }
      }
      assertEquals(
          mapping.executableLines,
          covered,
          clazz.getSimpleName() + ": union of probe lines doesn't match executable lines");
    }
  }

  @Test
  void probeToLines_areSorted() throws Exception {
    for (Class<?> clazz : allSampleClasses()) {
      ClassProbeMapping mapping = buildMapping(clazz);
      for (int p = 0; p < mapping.probeToLines.length; p++) {
        int[] lines = mapping.probeToLines[p];
        for (int i = 1; i < lines.length; i++) {
          assertTrue(
              lines[i] > lines[i - 1],
              clazz.getSimpleName()
                  + ": probe "
                  + p
                  + " lines not sorted: "
                  + Arrays.toString(lines));
        }
      }
    }
  }

  @Test
  void probeToLines_lengthMatchesProbeCount() throws Exception {
    for (Class<?> clazz : allSampleClasses()) {
      byte[] bytes = bytecodeFor(clazz);
      int probeCount = countProbes(bytes);
      ClassProbeMapping mapping = buildMapping(clazz);
      assertEquals(
          probeCount,
          mapping.probeToLines.length,
          clazz.getSimpleName() + ": probeToLines length mismatch");
    }
  }

  @Test
  void sourceFile_isPopulated() throws Exception {
    for (Class<?> clazz : allSampleClasses()) {
      ClassProbeMapping mapping = buildMapping(clazz);
      assertNotNull(mapping.sourceFile, clazz.getSimpleName() + ": sourceFile should not be null");
    }
  }

  // ===== Tests: specific behaviours and edge cases =====

  @Test
  void classIdAndClassName_arePreserved() throws Exception {
    byte[] bytes = bytecodeFor(SimpleLinear.class);
    long classId = CRC64.classId(bytes);
    String className = SimpleLinear.class.getName().replace('.', '/');
    int probeCount = countProbes(bytes);

    ClassProbeMapping mapping =
        ClassProbeMappingBuilder.build(classId, className, probeCount, bytes);

    assertEquals(classId, mapping.classId);
    assertEquals(className, mapping.className);
  }

  @Test
  void emptyMethod_hasExecutableLines() throws Exception {
    ClassProbeMapping mapping = buildMapping(EmptyMethod.class);
    assertFalse(
        mapping.executableLines.isEmpty(), "Empty method should still have executable lines");
    assertTrue(mapping.probeToLines.length > 0, "Empty method class should have probes");
  }

  @Test
  void emptyInterface_hasNoExecutableLinesAndZeroProbes() throws Exception {
    byte[] bytes = bytecodeFor(EmptyInterface.class);
    long classId = CRC64.classId(bytes);
    String className = EmptyInterface.class.getName().replace('.', '/');
    int probeCount = countProbes(bytes);

    assertEquals(0, probeCount, "Empty interface should have zero probes");

    ClassProbeMapping mapping =
        ClassProbeMappingBuilder.build(classId, className, probeCount, bytes);

    assertEquals(0, mapping.probeToLines.length);
    assertTrue(mapping.executableLines.isEmpty());
  }

  @Test
  void conditionalFallThrough_probeCoversLinesAcrossBranch() throws Exception {
    ClassProbeMapping mapping = buildMapping(ConditionalFallThrough.class);

    // The if-body probe covers: int a = 1, if(x>0), a = 2 — three lines. The merge point
    // after the if breaks the predecessor chain, so the return probe only covers lines after
    // the merge. With the old buggy implementation the fall-through edge was broken after the
    // probed jump, so the if-body probe would only cover 1 line (a = 2), giving max=2.
    int maxProbeLines = 0;
    for (int[] lines : mapping.probeToLines) {
      maxProbeLines = Math.max(maxProbeLines, lines.length);
    }
    assertTrue(
        maxProbeLines >= 3,
        "Expected a probe covering 3+ lines (if-body path including lines before the branch), "
            + "max was "
            + maxProbeLines);
  }

  @Test
  void multipleConditions_probeCoversAllPrecedingLines() throws Exception {
    ClassProbeMapping mapping = buildMapping(MultipleConditions.class);

    // The first if-body probe covers: int result=0, if(x>0), result=1 — three lines.
    // Merge points between conditionals break the predecessor chain, so no single probe
    // spans both ifs. With the old buggy implementation, the if-body probes lose their
    // predecessor link to the branch instruction, covering only the if-body line (max=2).
    int maxProbeLines = 0;
    for (int[] lines : mapping.probeToLines) {
      maxProbeLines = Math.max(maxProbeLines, lines.length);
    }
    assertTrue(
        maxProbeLines >= 3,
        "Expected a probe covering 3+ lines (if-body path including lines before the branch), "
            + "max was "
            + maxProbeLines);
  }

  @Test
  void tableSwitch_allCasesHaveProbes() throws Exception {
    ClassProbeMapping mapping = buildMapping(TableSwitch.class);

    // The table switch has 5 outcomes (case 0-3 + default), each returning a value.
    // Each case should have its own probe. With the old buggy implementation,
    // later switch target probes were silently dropped.
    int nonEmptyProbes = 0;
    for (int[] lines : mapping.probeToLines) {
      if (lines.length > 0) {
        nonEmptyProbes++;
      }
    }

    // At least 5 non-empty probes (one per case + default), plus constructor probe
    assertTrue(
        nonEmptyProbes >= 6,
        "Expected at least 6 non-empty probes (5 switch branches + constructor), got "
            + nonEmptyProbes);
  }

  @Test
  void lookupSwitch_allCasesHaveProbes() throws Exception {
    ClassProbeMapping mapping = buildMapping(LookupSwitch.class);

    int nonEmptyProbes = 0;
    for (int[] lines : mapping.probeToLines) {
      if (lines.length > 0) {
        nonEmptyProbes++;
      }
    }

    // At least 4 non-empty probes (case 100, 200, 300, default) + constructor
    assertTrue(
        nonEmptyProbes >= 5,
        "Expected at least 5 non-empty probes (4 switch branches + constructor), got "
            + nonEmptyProbes);
  }

  @Test
  void switchWithSharedTargets_allTargetsHaveProbes() throws Exception {
    ClassProbeMapping mapping = buildMapping(SwitchWithSharedTargets.class);

    int nonEmptyProbes = 0;
    for (int[] lines : mapping.probeToLines) {
      if (lines.length > 0) {
        nonEmptyProbes++;
      }
    }

    // At least 3 non-empty probes (small, medium, other) + constructor
    assertTrue(
        nonEmptyProbes >= 4,
        "Expected at least 4 non-empty probes (3 shared switch targets + constructor), got "
            + nonEmptyProbes);
  }

  @Test
  void multipleMethods_haveIndependentProbes() throws Exception {
    ClassProbeMapping mapping = buildMapping(MultipleMethods.class);

    // Each of the 3 methods + the constructor should have its own probe(s).
    // probeCount should be at least 4.
    assertTrue(
        mapping.probeToLines.length >= 4,
        "Expected at least 4 probes for 3 methods + constructor, got "
            + mapping.probeToLines.length);

    // No two probes should cover the exact same set of lines
    // (each method is independent and has distinct lines)
    for (int i = 0; i < mapping.probeToLines.length; i++) {
      for (int j = i + 1; j < mapping.probeToLines.length; j++) {
        if (mapping.probeToLines[i].length > 0 && mapping.probeToLines[j].length > 0) {
          assertFalse(
              Arrays.equals(mapping.probeToLines[i], mapping.probeToLines[j]),
              "Probes " + i + " and " + j + " should not cover identical line sets");
        }
      }
    }
  }

  @Test
  void tryCatch_exceptionPathHasProbe() throws Exception {
    ClassProbeMapping mapping = buildMapping(TryCatch.class);

    // The try block and catch block should be on different probes.
    // Both should have non-empty line sets.
    int nonEmptyProbes = 0;
    for (int[] lines : mapping.probeToLines) {
      if (lines.length > 0) {
        nonEmptyProbes++;
      }
    }

    // At least: try-path return, catch-path return, constructor
    assertTrue(
        nonEmptyProbes >= 3,
        "Expected at least 3 non-empty probes (try + catch + constructor), got " + nonEmptyProbes);
  }

  @Test
  void earlyReturn_laterCodeIsStillReachable() throws Exception {
    ClassProbeMapping mapping = buildMapping(EarlyReturn.class);

    // The code after the early return guard should be covered by a probe
    // that also includes the initial guard check line.
    int firstLine = mapping.executableLines.nextSetBit(0);
    int lastLine = mapping.executableLines.previousSetBit(mapping.executableLines.length());

    // Find maximum line count covered by any single probe
    int maxProbeLines = 0;
    for (int[] lines : mapping.probeToLines) {
      maxProbeLines = Math.max(maxProbeLines, lines.length);
    }

    // The non-early-return path should cover multiple lines
    assertTrue(
        maxProbeLines >= 3,
        "Expected at least one probe covering 3+ lines for the non-early-return path, got "
            + maxProbeLines);
  }

  // ===== Helpers =====

  private static final Class<?>[] ALL_SAMPLE_CLASSES = {
    SimpleLinear.class,
    IfElseBranch.class,
    MultipleConditions.class,
    ConditionalFallThrough.class,
    TableSwitch.class,
    LookupSwitch.class,
    SwitchWithSharedTargets.class,
    TryCatch.class,
    TryCatchFinally.class,
    ForLoop.class,
    WhileLoop.class,
    NestedConditions.class,
    MultipleMethods.class,
    EmptyMethod.class,
    TernaryExpression.class,
    ShortCircuit.class,
    EarlyReturn.class,
  };

  private static Class<?>[] allSampleClasses() {
    return ALL_SAMPLE_CLASSES;
  }

  private static ClassProbeMapping buildMapping(Class<?> clazz) throws Exception {
    byte[] bytes = bytecodeFor(clazz);
    long classId = CRC64.classId(bytes);
    String className = clazz.getName().replace('.', '/');
    int probeCount = countProbes(bytes);
    return ClassProbeMappingBuilder.build(classId, className, probeCount, bytes);
  }

  /**
   * Verifies that ClassProbeMappingBuilder produces results consistent with a direct JaCoCo
   * Analyzer run. The builder and the reference use the same JaCoCo APIs but exercise the
   * translation layer (line iteration, BitSet/array conversion) independently.
   */
  private void assertMatchesJaCoCo(Class<?> clazz) throws Exception {
    byte[] bytes = bytecodeFor(clazz);
    long classId = CRC64.classId(bytes);
    String className = clazz.getName().replace('.', '/');
    int probeCount = countProbes(bytes);

    ClassProbeMapping actual =
        ClassProbeMappingBuilder.build(classId, className, probeCount, bytes);

    // Reference: executable lines
    BitSet expectedExec = referenceExecutableLines(classId, className, bytes);
    assertEquals(
        expectedExec, actual.executableLines, clazz.getSimpleName() + ": executable lines");

    // Reference: probe-to-lines
    for (int p = 0; p < probeCount; p++) {
      int[] expectedLines = referenceProbeLines(classId, className, probeCount, bytes, p);
      assertArrayEquals(
          expectedLines, actual.probeToLines[p], clazz.getSimpleName() + ": probe " + p + " lines");
    }
  }

  // --- Reference implementation using JaCoCo's Analyzer directly ---

  private static BitSet referenceExecutableLines(long classId, String className, byte[] bytes)
      throws Exception {
    IClassCoverage cc = runJaCoCoAnalysis(classId, className, bytes, null);
    BitSet lines = new BitSet();
    if (cc != null && cc.getFirstLine() != ISourceNode.UNKNOWN_LINE) {
      for (int line = cc.getFirstLine(); line <= cc.getLastLine(); line++) {
        if (cc.getLine(line).getInstructionCounter().getTotalCount() > 0) {
          lines.set(line);
        }
      }
    }
    return lines;
  }

  private static int[] referenceProbeLines(
      long classId, String className, int probeCount, byte[] bytes, int probeId) throws Exception {
    boolean[] probes = new boolean[probeCount];
    probes[probeId] = true;
    IClassCoverage cc = runJaCoCoAnalysis(classId, className, bytes, probes);
    BitSet lines = new BitSet();
    if (cc != null && cc.getFirstLine() != ISourceNode.UNKNOWN_LINE) {
      for (int line = cc.getFirstLine(); line <= cc.getLastLine(); line++) {
        if (cc.getLine(line).getInstructionCounter().getCoveredCount() > 0) {
          lines.set(line);
        }
      }
    }
    return bitSetToArray(lines);
  }

  private static IClassCoverage runJaCoCoAnalysis(
      long classId, String className, byte[] bytes, boolean[] probes) throws Exception {
    ExecutionDataStore store = new ExecutionDataStore();
    if (probes != null) {
      store.put(new ExecutionData(classId, className, probes));
    }
    CoverageBuilder builder = new CoverageBuilder();
    Analyzer analyzer = new Analyzer(store, builder);
    analyzer.analyzeClass(bytes, className);
    for (IClassCoverage cc : builder.getClasses()) {
      return cc;
    }
    return null;
  }

  // --- Utility methods ---

  private static byte[] bytecodeFor(Class<?> clazz) throws IOException {
    String resource = clazz.getName().replace('.', '/') + ".class";
    try (InputStream is = clazz.getClassLoader().getResourceAsStream(resource)) {
      assertNotNull(is, "Could not find class file for " + clazz.getName());
      return readAllBytes(is);
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

  private static int[] bitSetToArray(BitSet bs) {
    int[] result = new int[bs.cardinality()];
    int idx = 0;
    for (int bit = bs.nextSetBit(0); bit >= 0; bit = bs.nextSetBit(bit + 1)) {
      result[idx++] = bit;
    }
    return result;
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
