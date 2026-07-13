package datadog.trace.civisibility.coverage.line;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import org.jacoco.core.analysis.ISourceNode;
import org.jacoco.core.internal.analysis.filter.Filters;
import org.jacoco.core.internal.analysis.filter.IFilter;
import org.jacoco.core.internal.analysis.filter.IFilterContext;
import org.jacoco.core.internal.analysis.filter.IFilterOutput;
import org.jacoco.core.internal.analysis.filter.Replacements;
import org.jacoco.core.internal.flow.ClassProbesAdapter;
import org.jacoco.core.internal.flow.ClassProbesVisitor;
import org.jacoco.core.internal.flow.IFrame;
import org.jacoco.core.internal.flow.LabelInfo;
import org.jacoco.core.internal.flow.MethodProbesVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TryCatchBlockNode;

/**
 * Probe-value-independent structural model of a class' line coverage.
 *
 * <p>Jacoco's {@link org.jacoco.core.analysis.Analyzer} re-parses and re-analyzes a class' bytecode
 * for every test that covers it, which dominates the cost of line-coverage reporting. The structure
 * that maps probes to covered lines, however, depends only on the bytecode — not on which probes a
 * given test executed. This model captures that structure once (mirroring Jacoco's own analysis but
 * carrying symbolic per-branch probe sets instead of concrete booleans) so that resolving the
 * covered lines for a test becomes a cheap set intersection: a line is covered iff any probe that
 * structurally covers it was executed.
 *
 * <p>Correctness is not assumed: {@link #matches} lets the caller verify the model against Jacoco's
 * real analysis for an observed probe array before trusting it, falling back otherwise.
 */
public final class ClassCoverageModel {

  /**
   * Sentinel cached for classes that must not use a model (build failed or the model did not
   * reproduce Jacoco). Callers compare by identity and fall back to Jacoco's analysis. Having a
   * single cache with this sentinel (rather than a separate "unmodellable" set) keeps the
   * first-encounter decision atomic and avoids a class being treated as both modelled and not.
   */
  static final ClassCoverageModel UNMODELLABLE = new ClassCoverageModel(Collections.emptyMap());

  // For each source line, the set of probe ids that cover it. The line is covered by a test iff the
  // test executed at least one of these probes.
  private final Map<Integer, BitSet> lineProbes;

  private ClassCoverageModel(Map<Integer, BitSet> lineProbes) {
    this.lineProbes = lineProbes;
  }

  /** Resolves the lines covered by a test given the probes it executed. */
  public BitSet coveredLines(boolean[] probes) {
    BitSet covered = new BitSet();
    for (Map.Entry<Integer, BitSet> e : lineProbes.entrySet()) {
      BitSet probeSet = e.getValue();
      for (int p = probeSet.nextSetBit(0); p >= 0; p = probeSet.nextSetBit(p + 1)) {
        if (p < probes.length && probes[p]) {
          covered.set(e.getKey());
          break;
        }
      }
    }
    return covered;
  }

  /**
   * Verifies the model reproduces Jacoco's covered lines for a given probe array. Used to guard the
   * optimization: a mismatch (e.g. an unmodelled filter interaction) means the caller should fall
   * back to Jacoco's analysis for this class rather than trust the model.
   */
  public boolean matches(boolean[] probes, BitSet jacocoCoveredLines) {
    return coveredLines(probes).equals(jacocoCoveredLines);
  }

  /** Builds the model by analyzing the class bytecode once. */
  public static ClassCoverageModel build(byte[] classBytes) {
    Map<Integer, BitSet> lineProbes = new HashMap<>();
    ClassReader reader = new ClassReader(classBytes);
    reader.accept(new ClassProbesAdapter(new ModelClassAnalyzer(lineProbes), false), 0);
    return new ClassCoverageModel(lineProbes);
  }

  /** A single bytecode instruction with, per outgoing branch, the set of probes that cover it. */
  private static final class ProbeInstruction {
    private final int line;
    private int branches;
    private final Map<Integer, BitSet> branchProbes = new HashMap<>();
    private ProbeInstruction predecessor;
    private int predecessorBranch;

    ProbeInstruction(int line) {
      this.line = line;
    }

    /** Structural CFG edge to a target instruction (mirrors Jacoco addBranch(Instruction,int)). */
    void addBranch(ProbeInstruction target, int branch) {
      branches++;
      target.predecessor = this;
      target.predecessorBranch = branch;
      BitSet targetProbes = target.coveringProbes();
      for (int p = targetProbes.nextSetBit(0); p >= 0; p = targetProbes.nextSetBit(p + 1)) {
        propagate(this, branch, p);
      }
    }

    /** Branch whose coverage is directly gated by a probe (mirrors addBranch(boolean,int)). */
    void addProbeBranch(int probeId, int branch) {
      branches++;
      propagate(this, branch, probeId);
    }

    // Propagates "covered by probeId along this branch" up the predecessor chain. Unlike Jacoco's
    // boolean propagation (which stops as soon as an instruction is covered by anything), this
    // continues until the instruction already carries this specific probe, so every probe that can
    // reach an instruction is recorded — needed to answer the query for any probe array.
    private static void propagate(ProbeInstruction insn, int branch, int probeId) {
      while (insn != null) {
        BitSet bp = insn.branchProbes.get(branch);
        if (bp == null) {
          bp = new BitSet();
          insn.branchProbes.put(branch, bp);
        }
        if (bp.get(probeId)) {
          break;
        }
        bp.set(probeId);
        branch = insn.predecessorBranch;
        insn = insn.predecessor;
      }
    }

    /** Probes covering this instruction (union over its branches). */
    BitSet coveringProbes() {
      BitSet union = new BitSet();
      for (BitSet bp : branchProbes.values()) {
        union.or(bp);
      }
      return union;
    }

    ProbeInstruction merge(ProbeInstruction other) {
      ProbeInstruction result = new ProbeInstruction(line);
      result.branches = branches;
      for (Map.Entry<Integer, BitSet> e : branchProbes.entrySet()) {
        result.branchProbes.put(e.getKey(), (BitSet) e.getValue().clone());
      }
      for (Map.Entry<Integer, BitSet> e : other.branchProbes.entrySet()) {
        result.branchProbes.computeIfAbsent(e.getKey(), k -> new BitSet()).or(e.getValue());
      }
      return result;
    }

    ProbeInstruction replaceBranches(Replacements replacements, Mapper mapper) {
      ProbeInstruction result = new ProbeInstruction(line);
      int branchIndex = 0;
      for (java.util.Collection<Replacements.InstructionBranch> newBranch : replacements.values()) {
        BitSet probes = new BitSet();
        for (Replacements.InstructionBranch from : newBranch) {
          BitSet fromProbes = mapper.apply(from.instruction).branchProbes.get(from.branch);
          if (fromProbes != null) {
            probes.or(fromProbes);
          }
        }
        if (!probes.isEmpty()) {
          result.branchProbes.put(branchIndex, probes);
        }
        branchIndex++;
      }
      result.branches = branchIndex;
      return result;
    }

    int getLine() {
      return line;
    }
  }

  /** {@code Function<AbstractInsnNode, ProbeInstruction>}. */
  private interface Mapper {
    ProbeInstruction apply(AbstractInsnNode node);
  }

  /** Builds {@link ProbeInstruction}s of a method (mirrors Jacoco InstructionsBuilder). */
  private static final class InstructionsBuilder {
    private int currentLine = ISourceNode.UNKNOWN_LINE;
    private ProbeInstruction currentInsn;
    private final Map<AbstractInsnNode, ProbeInstruction> instructions = new HashMap<>();
    private final Map<Label, ProbeInstruction> labelToInsn = new HashMap<>();
    private final List<Label> currentLabel = new ArrayList<>(2);
    private final List<Jump> jumps = new ArrayList<>();

    void setCurrentLine(int line) {
      currentLine = line;
    }

    void addLabel(Label label) {
      currentLabel.add(label);
      if (!LabelInfo.isSuccessor(label)) {
        noSuccessor();
      }
    }

    void addInstruction(AbstractInsnNode node) {
      ProbeInstruction insn = new ProbeInstruction(currentLine);
      int labelCount = currentLabel.size();
      if (labelCount > 0) {
        for (int i = labelCount; --i >= 0; ) {
          labelToInsn.put(currentLabel.get(i), insn);
        }
        currentLabel.clear();
      }
      if (currentInsn != null) {
        currentInsn.addBranch(insn, 0);
      }
      currentInsn = insn;
      instructions.put(node, insn);
    }

    void noSuccessor() {
      currentInsn = null;
    }

    void addJump(Label target, int branch) {
      jumps.add(new Jump(currentInsn, target, branch));
    }

    void addProbe(int probeId, int branch) {
      currentInsn.addProbeBranch(probeId, branch);
    }

    Map<AbstractInsnNode, ProbeInstruction> getInstructions() {
      for (Jump j : jumps) {
        j.source.addBranch(labelToInsn.get(j.target), j.branch);
      }
      return instructions;
    }

    private static final class Jump {
      private final ProbeInstruction source;
      private final Label target;
      private final int branch;

      Jump(ProbeInstruction source, Label target, int branch) {
        this.source = source;
        this.target = target;
        this.branch = branch;
      }
    }
  }

  /** Mirrors Jacoco MethodAnalyzer: drives {@link InstructionsBuilder} from the probe visitor. */
  private static class ModelMethodAnalyzer extends MethodProbesVisitor {
    private final InstructionsBuilder builder;
    private AbstractInsnNode currentNode;

    ModelMethodAnalyzer(InstructionsBuilder builder) {
      this.builder = builder;
    }

    @Override
    public void accept(MethodNode methodNode, MethodVisitor methodVisitor) {
      methodVisitor.visitCode();
      for (TryCatchBlockNode n : methodNode.tryCatchBlocks) {
        n.accept(methodVisitor);
      }
      for (AbstractInsnNode i : methodNode.instructions) {
        currentNode = i;
        i.accept(methodVisitor);
      }
      methodVisitor.visitEnd();
    }

    @Override
    public void visitLabel(Label label) {
      builder.addLabel(label);
    }

    @Override
    public void visitLineNumber(int line, Label start) {
      builder.setCurrentLine(line);
    }

    @Override
    public void visitInsn(int opcode) {
      builder.addInstruction(currentNode);
    }

    @Override
    public void visitIntInsn(int opcode, int operand) {
      builder.addInstruction(currentNode);
    }

    @Override
    public void visitVarInsn(int opcode, int var) {
      builder.addInstruction(currentNode);
    }

    @Override
    public void visitTypeInsn(int opcode, String type) {
      builder.addInstruction(currentNode);
    }

    @Override
    public void visitFieldInsn(int opcode, String owner, String name, String desc) {
      builder.addInstruction(currentNode);
    }

    @Override
    public void visitMethodInsn(int opcode, String owner, String name, String desc, boolean itf) {
      builder.addInstruction(currentNode);
    }

    @Override
    public void visitInvokeDynamicInsn(String name, String desc, Handle bsm, Object... bsmArgs) {
      builder.addInstruction(currentNode);
    }

    @Override
    public void visitJumpInsn(int opcode, Label label) {
      builder.addInstruction(currentNode);
      builder.addJump(label, 1);
    }

    @Override
    public void visitLdcInsn(Object cst) {
      builder.addInstruction(currentNode);
    }

    @Override
    public void visitIincInsn(int var, int increment) {
      builder.addInstruction(currentNode);
    }

    @Override
    public void visitTableSwitchInsn(int min, int max, Label dflt, Label... labels) {
      visitSwitchInsn(dflt, labels);
    }

    @Override
    public void visitLookupSwitchInsn(Label dflt, int[] keys, Label[] labels) {
      visitSwitchInsn(dflt, labels);
    }

    private void visitSwitchInsn(Label dflt, Label[] labels) {
      builder.addInstruction(currentNode);
      LabelInfo.resetDone(labels);
      int branch = 0;
      builder.addJump(dflt, branch);
      LabelInfo.setDone(dflt);
      for (Label l : labels) {
        if (!LabelInfo.isDone(l)) {
          branch++;
          builder.addJump(l, branch);
          LabelInfo.setDone(l);
        }
      }
    }

    @Override
    public void visitMultiANewArrayInsn(String desc, int dims) {
      builder.addInstruction(currentNode);
    }

    @Override
    public void visitProbe(int probeId) {
      builder.addProbe(probeId, 0);
      builder.noSuccessor();
    }

    @Override
    public void visitJumpInsnWithProbe(int opcode, Label label, int probeId, IFrame frame) {
      builder.addInstruction(currentNode);
      builder.addProbe(probeId, 1);
    }

    @Override
    public void visitInsnWithProbe(int opcode, int probeId) {
      builder.addInstruction(currentNode);
      builder.addProbe(probeId, 0);
    }

    @Override
    public void visitTableSwitchInsnWithProbes(
        int min, int max, Label dflt, Label[] labels, IFrame frame) {
      visitSwitchInsnWithProbes(dflt, labels);
    }

    @Override
    public void visitLookupSwitchInsnWithProbes(
        Label dflt, int[] keys, Label[] labels, IFrame frame) {
      visitSwitchInsnWithProbes(dflt, labels);
    }

    private void visitSwitchInsnWithProbes(Label dflt, Label[] labels) {
      builder.addInstruction(currentNode);
      LabelInfo.resetDone(dflt);
      LabelInfo.resetDone(labels);
      int branch = 0;
      visitSwitchTarget(dflt, branch);
      for (Label l : labels) {
        branch++;
        visitSwitchTarget(l, branch);
      }
    }

    private void visitSwitchTarget(Label label, int branch) {
      int id = LabelInfo.getProbeId(label);
      if (!LabelInfo.isDone(label)) {
        if (id == LabelInfo.NO_PROBE) {
          builder.addJump(label, branch);
        } else {
          builder.addProbe(id, branch);
        }
        LabelInfo.setDone(label);
      }
    }
  }

  /**
   * Applies Jacoco's filters (via the {@link IFilterOutput} contract) and folds each surviving
   * instruction's covering probes into the per-line probe sets. Mirrors Jacoco
   * MethodCoverageCalculator, but accumulates probe sets rather than counters.
   */
  private static final class ModelMethodCalculator implements IFilterOutput {
    private final Map<AbstractInsnNode, ProbeInstruction> instructions;
    private final HashSet<AbstractInsnNode> ignored = new HashSet<>();
    private final Map<AbstractInsnNode, AbstractInsnNode> merged = new HashMap<>();
    private final Map<AbstractInsnNode, Replacements> replacements = new HashMap<>();

    ModelMethodCalculator(Map<AbstractInsnNode, ProbeInstruction> instructions) {
      this.instructions = instructions;
    }

    void calculate(Map<Integer, BitSet> lineProbes) {
      applyMerges();
      applyReplacements();
      for (Map.Entry<AbstractInsnNode, ProbeInstruction> entry : instructions.entrySet()) {
        if (!ignored.contains(entry.getKey())) {
          ProbeInstruction insn = entry.getValue();
          int line = insn.getLine();
          if (line == ISourceNode.UNKNOWN_LINE) {
            continue;
          }
          BitSet probes = insn.coveringProbes();
          if (!probes.isEmpty()) {
            lineProbes.computeIfAbsent(line, k -> new BitSet()).or(probes);
          }
        }
      }
    }

    private void applyMerges() {
      for (Map.Entry<AbstractInsnNode, AbstractInsnNode> entry : merged.entrySet()) {
        AbstractInsnNode node = entry.getKey();
        ProbeInstruction instruction = instructions.get(node);
        AbstractInsnNode representativeNode = findRepresentative(node);
        ignored.add(node);
        instructions.put(
            representativeNode, instructions.get(representativeNode).merge(instruction));
        entry.setValue(representativeNode);
      }
      for (Map.Entry<AbstractInsnNode, AbstractInsnNode> entry : merged.entrySet()) {
        instructions.put(entry.getKey(), instructions.get(entry.getValue()));
      }
    }

    private void applyReplacements() {
      Mapper mapper = instructions::get;
      for (Map.Entry<AbstractInsnNode, Replacements> entry : replacements.entrySet()) {
        AbstractInsnNode node = entry.getKey();
        instructions.put(node, instructions.get(node).replaceBranches(entry.getValue(), mapper));
      }
    }

    private AbstractInsnNode findRepresentative(AbstractInsnNode i) {
      AbstractInsnNode r;
      while ((r = merged.get(i)) != null) {
        i = r;
      }
      return i;
    }

    @Override
    public void ignore(AbstractInsnNode fromInclusive, AbstractInsnNode toInclusive) {
      for (AbstractInsnNode i = fromInclusive; i != toInclusive; i = i.getNext()) {
        ignored.add(i);
      }
      ignored.add(toInclusive);
    }

    @Override
    public void merge(AbstractInsnNode i1, AbstractInsnNode i2) {
      i1 = findRepresentative(i1);
      i2 = findRepresentative(i2);
      if (i1 != i2) {
        merged.put(i2, i1);
      }
    }

    @Override
    public void replaceBranches(AbstractInsnNode source, Replacements newBranches) {
      replacements.put(source, newBranches);
    }
  }

  /** Mirrors Jacoco ClassAnalyzer: drives per-method analysis and filter application. */
  private static final class ModelClassAnalyzer extends ClassProbesVisitor
      implements IFilterContext {
    private final Map<Integer, BitSet> lineProbes;
    private final IFilter filter = Filters.all();

    private String className;
    private String superName;
    private String sourceFileName;
    private String sourceDebugExtension;
    private final HashSet<String> classAnnotations = new HashSet<>();
    private final HashSet<String> classAttributes = new HashSet<>();

    ModelClassAnalyzer(Map<Integer, BitSet> lineProbes) {
      this.lineProbes = lineProbes;
    }

    @Override
    public void visit(
        int version,
        int access,
        String name,
        String signature,
        String superName,
        String[] interfaces) {
      this.className = name;
      this.superName = superName;
    }

    @Override
    public org.objectweb.asm.AnnotationVisitor visitAnnotation(String desc, boolean visible) {
      classAnnotations.add(desc);
      return super.visitAnnotation(desc, visible);
    }

    @Override
    public void visitAttribute(org.objectweb.asm.Attribute attribute) {
      classAttributes.add(attribute.type);
    }

    @Override
    public void visitSource(String source, String debug) {
      this.sourceFileName = source;
      this.sourceDebugExtension = debug;
    }

    @Override
    public MethodProbesVisitor visitMethod(
        int access, String name, String desc, String signature, String[] exceptions) {
      InstructionsBuilder builder = new InstructionsBuilder();
      return new ModelMethodAnalyzer(builder) {
        @Override
        public void accept(MethodNode methodNode, MethodVisitor methodVisitor) {
          super.accept(methodNode, methodVisitor);
          Map<AbstractInsnNode, ProbeInstruction> instructions = builder.getInstructions();
          ModelMethodCalculator calculator = new ModelMethodCalculator(instructions);
          filter.filter(methodNode, ModelClassAnalyzer.this, calculator);
          calculator.calculate(lineProbes);
        }
      };
    }

    @Override
    public void visitTotalProbeCount(int count) {
      // nothing to do
    }

    // IFilterContext

    @Override
    public String getClassName() {
      return className;
    }

    @Override
    public String getSuperClassName() {
      return superName;
    }

    @Override
    public java.util.Set<String> getClassAnnotations() {
      return classAnnotations;
    }

    @Override
    public java.util.Set<String> getClassAttributes() {
      return classAttributes;
    }

    @Override
    public String getSourceFileName() {
      return sourceFileName;
    }

    @Override
    public String getSourceDebugExtension() {
      return sourceDebugExtension;
    }
  }
}
