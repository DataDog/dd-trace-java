package datadog.trace.codecoverage;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.jacoco.core.internal.flow.ClassProbesAdapter;
import org.jacoco.core.internal.flow.ClassProbesVisitor;
import org.jacoco.core.internal.flow.IFrame;
import org.jacoco.core.internal.flow.LabelInfo;
import org.jacoco.core.internal.flow.MethodProbesVisitor;
import org.jacoco.core.internal.instr.InstrSupport;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TryCatchBlockNode;

/**
 * Builds a {@link ClassProbeMapping} by parsing the class bytecode once using JaCoCo's {@link
 * ClassProbesAdapter}, building a simplified instruction graph, and walking predecessor chains to
 * determine which lines each probe covers.
 *
 * <p>This replaces the previous N+1 pass approach (one {@code Analyzer} pass per probe plus one for
 * executable lines) with a single-pass design that is significantly faster for classes with many
 * probes.
 */
final class ClassProbeMappingBuilder {

  static ClassProbeMapping build(
      long classId, String className, int probeCount, byte[] classBytes) {
    ClassReader reader = InstrSupport.classReaderFor(classBytes);
    ProbeMappingVisitor visitor = new ProbeMappingVisitor();
    ClassProbesAdapter adapter = new ClassProbesAdapter(visitor, false);
    reader.accept(adapter, 0);
    return visitor.toMapping(classId, className, probeCount);
  }

  /** Simplified instruction node with a line number and a single predecessor link. */
  static final class ProbeNode {
    final int line;
    ProbeNode predecessor;

    ProbeNode(int line) {
      this.line = line;
    }
  }

  /** A deferred jump from a source instruction to a target label. */
  static final class Jump {
    final ProbeNode source;
    final Label target;
    final int branch;

    Jump(ProbeNode source, Label target, int branch) {
      this.source = source;
      this.target = target;
      this.branch = branch;
    }
  }

  /**
   * Class-level visitor that collects source file info and delegates method visiting to {@link
   * MethodMapper}.
   */
  private static final class ProbeMappingVisitor extends ClassProbesVisitor {
    private String sourceFile;
    private final BitSet executableLines = new BitSet();
    private final Map<Integer, BitSet> probeToLines = new HashMap<>();

    @Override
    public void visitSource(String source, String debug) {
      sourceFile = source;
    }

    @Override
    public MethodProbesVisitor visitMethod(
        int access, String name, String desc, String signature, String[] exceptions) {
      return new MethodMapper(executableLines, probeToLines);
    }

    @Override
    public void visitTotalProbeCount(int count) {
      // no-op; we get probeCount from the caller
    }

    ClassProbeMapping toMapping(long classId, String className, int probeCount) {
      int[][] probeToLinesArray = new int[probeCount][];
      for (int p = 0; p < probeCount; p++) {
        BitSet lines = probeToLines.get(p);
        probeToLinesArray[p] = (lines != null) ? bitSetToArray(lines) : new int[0];
      }
      return new ClassProbeMapping(classId, className, sourceFile, executableLines, probeToLinesArray);
    }

    private static int[] bitSetToArray(BitSet bs) {
      int[] result = new int[bs.cardinality()];
      int idx = 0;
      for (int bit = bs.nextSetBit(0); bit >= 0; bit = bs.nextSetBit(bit + 1)) {
        result[idx++] = bit;
      }
      return result;
    }
  }

  /**
   * Method-level visitor that builds a simplified instruction graph (with predecessor links) and
   * records probe-to-instruction associations. After all instructions are replayed, jump targets are
   * wired and predecessor chains are walked to collect covered lines per probe.
   *
   * <p>This replicates the logic of JaCoCo's {@code InstructionsBuilder} and {@code
   * MethodAnalyzer}, which are package-private and cannot be used directly from this package.
   */
  private static final class MethodMapper extends MethodProbesVisitor {
    private static final int UNKNOWN_LINE = -1;

    private final BitSet executableLines;
    private final Map<Integer, BitSet> probeToLines;

    // Per-method state
    private int currentLine = UNKNOWN_LINE;
    private ProbeNode currentInsn;
    private final List<Label> pendingLabels = new ArrayList<>(2);
    private final Map<Label, ProbeNode> labelToInsn = new HashMap<>();
    private final List<Jump> jumps = new ArrayList<>();
    private final Map<Integer, ProbeNode> probeInsns = new HashMap<>();

    MethodMapper(BitSet executableLines, Map<Integer, BitSet> probeToLines) {
      this.executableLines = executableLines;
      this.probeToLines = probeToLines;
    }

    // --- accept: drives the visitor, then post-processes ---

    @Override
    public void accept(MethodNode methodNode, MethodVisitor methodVisitor) {
      // Replay instructions through methodVisitor (MethodProbesAdapter)
      methodVisitor.visitCode();
      for (TryCatchBlockNode n : methodNode.tryCatchBlocks) {
        n.accept(methodVisitor);
      }
      for (AbstractInsnNode i : methodNode.instructions) {
        i.accept(methodVisitor);
      }
      methodVisitor.visitEnd();

      // Wire jumps (may overwrite sequential predecessors at merge points)
      for (Jump j : jumps) {
        if (j.source != null) {
          ProbeNode target = labelToInsn.get(j.target);
          if (target != null) {
            target.predecessor = j.source;
          }
        }
      }

      // Walk predecessor chains for each probe
      for (Map.Entry<Integer, ProbeNode> e : probeInsns.entrySet()) {
        BitSet lines = new BitSet();
        ProbeNode node = e.getValue();
        while (node != null) {
          if (node.line > 0) {
            lines.set(node.line);
          }
          node = node.predecessor;
        }
        probeToLines.put(e.getKey(), lines);
      }
    }

    // --- Instruction building (replicates InstructionsBuilder logic) ---

    @Override
    public void visitLineNumber(int line, Label start) {
      currentLine = line;
    }

    @Override
    public void visitLabel(Label label) {
      pendingLabels.add(label);
      if (!LabelInfo.isSuccessor(label)) {
        currentInsn = null; // break sequential chain
      }
    }

    private void addInstruction() {
      ProbeNode insn = new ProbeNode(currentLine);
      // Associate pending labels with this instruction (for jump wiring)
      if (!pendingLabels.isEmpty()) {
        for (Label l : pendingLabels) {
          labelToInsn.put(l, insn);
        }
        pendingLabels.clear();
      }
      // Sequential predecessor link
      if (currentInsn != null) {
        insn.predecessor = currentInsn;
      }
      currentInsn = insn;
      // Track executable lines
      if (currentLine > 0) {
        executableLines.set(currentLine);
      }
    }

    private void addProbe(int probeId) {
      if (currentInsn != null) {
        probeInsns.put(probeId, currentInsn);
      }
      currentInsn = null; // noSuccessor (probe breaks chain)
    }

    private void addJump(Label target, int branch) {
      jumps.add(new Jump(currentInsn, target, branch));
    }

    // --- All instruction visit methods -> addInstruction() ---

    @Override
    public void visitInsn(int opcode) {
      addInstruction();
    }

    @Override
    public void visitIntInsn(int opcode, int operand) {
      addInstruction();
    }

    @Override
    public void visitVarInsn(int opcode, int var) {
      addInstruction();
    }

    @Override
    public void visitTypeInsn(int opcode, String type) {
      addInstruction();
    }

    @Override
    public void visitFieldInsn(int opcode, String owner, String name, String desc) {
      addInstruction();
    }

    @Override
    public void visitMethodInsn(int opcode, String owner, String name, String desc, boolean itf) {
      addInstruction();
    }

    @Override
    public void visitInvokeDynamicInsn(String name, String desc, Handle bsm, Object... bsmArgs) {
      addInstruction();
    }

    @Override
    public void visitLdcInsn(Object cst) {
      addInstruction();
    }

    @Override
    public void visitIincInsn(int var, int increment) {
      addInstruction();
    }

    @Override
    public void visitMultiANewArrayInsn(String desc, int dims) {
      addInstruction();
    }

    @Override
    public void visitJumpInsn(int opcode, Label label) {
      addInstruction();
      addJump(label, 1);
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
      addInstruction();
      LabelInfo.resetDone(labels);
      int branch = 0;
      addJump(dflt, branch);
      LabelInfo.setDone(dflt);
      for (Label l : labels) {
        if (!LabelInfo.isDone(l)) {
          branch++;
          addJump(l, branch);
          LabelInfo.setDone(l);
        }
      }
    }

    // --- Probe visit methods ---

    @Override
    public void visitProbe(int probeId) {
      addProbe(probeId);
    }

    @Override
    public void visitJumpInsnWithProbe(int opcode, Label label, int probeId, IFrame frame) {
      addInstruction();
      addProbe(probeId);
    }

    @Override
    public void visitInsnWithProbe(int opcode, int probeId) {
      addInstruction();
      addProbe(probeId);
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
      addInstruction();
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
          addJump(label, branch);
        } else {
          addProbe(id);
        }
        LabelInfo.setDone(label);
      }
    }
  }
}
