package datadog.trace.civisibility.coverage.probe;

import static org.objectweb.asm.Opcodes.AALOAD;
import static org.objectweb.asm.Opcodes.ACC_STATIC;
import static org.objectweb.asm.Opcodes.ARETURN;
import static org.objectweb.asm.Opcodes.ATHROW;
import static org.objectweb.asm.Opcodes.BALOAD;
import static org.objectweb.asm.Opcodes.BIPUSH;
import static org.objectweb.asm.Opcodes.DRETURN;
import static org.objectweb.asm.Opcodes.FRETURN;
import static org.objectweb.asm.Opcodes.F_NEW;
import static org.objectweb.asm.Opcodes.GETSTATIC;
import static org.objectweb.asm.Opcodes.GOTO;
import static org.objectweb.asm.Opcodes.ICONST_0;
import static org.objectweb.asm.Opcodes.IFEQ;
import static org.objectweb.asm.Opcodes.IFNE;
import static org.objectweb.asm.Opcodes.IFNONNULL;
import static org.objectweb.asm.Opcodes.IFNULL;
import static org.objectweb.asm.Opcodes.INVOKESPECIAL;
import static org.objectweb.asm.Opcodes.INVOKESTATIC;
import static org.objectweb.asm.Opcodes.IRETURN;
import static org.objectweb.asm.Opcodes.JSR;
import static org.objectweb.asm.Opcodes.LRETURN;
import static org.objectweb.asm.Opcodes.NEW;
import static org.objectweb.asm.Opcodes.RET;
import static org.objectweb.asm.Opcodes.RETURN;
import static org.objectweb.asm.Opcodes.SIPUSH;
import static org.objectweb.asm.Opcodes.UNINITIALIZED_THIS;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.LineNumberNode;
import org.objectweb.asm.tree.LookupSwitchInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TableSwitchInsnNode;
import org.objectweb.asm.tree.TryCatchBlockNode;

/**
 * Inserts line probes into one method.
 *
 * <p>Placement follows the dominator tree of the method's basic blocks, with every exception
 * handler treated as an extra entry: a probe proves that its block completed and, through
 * dominance, that every block dominating it completed. A block gets a probe if it returns or if one
 * of its successors is not dominated by it, so every path through a block reaches a probe that
 * proves it. A method whose code is on a single line gets one probe at entry instead.
 *
 * <p>A probe reads its slot and calls {@code ProbeRecorder.record} only when the slot is not set.
 * Slow paths that need a stack map frame which cannot be copied from the original code are placed
 * after the method body, where a frame with no locals is valid.
 *
 * <p>Lines that run before an exception leaves a block midway are recorded by exception table
 * entries that cost nothing until something is thrown: each run of instructions that adds a line to
 * the block gets an entry that records the lines reached so far and then rethrows, or continues to
 * the method's own handler, so behavior is unchanged.
 */
final class MethodProbeInserter {

  static final String RECORDER = "datadog/trace/api/civisibility/coverage/ProbeRecorder";

  private final String owner;
  private final MethodNode method;
  private final int classId;
  private final ClassProbesBuilder probes;
  private final boolean initializer;
  private final boolean frames;
  private final boolean exceptionProbes;

  private AbstractInsnNode[] insns;
  private int[] lineOf;
  private Map<LabelNode, Integer> labelIndex;
  private Set<LabelNode> jumpTargets;
  private int[] stackSizes;

  private int blockCount;
  private int root;
  private int[] blockOf;
  private int[] blockFirst;
  private int[] blockLast;
  private int[][] successors;
  private boolean[] reachable;
  private int[] idom;
  private int[][] blockLines;
  private int[] blockNode;
  private int[] blockProbe;

  private final InsnList tail = new InsnList();
  private final List<TryCatchBlockNode> uncaughtHandlers = new ArrayList<>();
  private final Map<TryCatchBlockNode, List<TryCatchBlockNode>> caughtHandlers =
      new IdentityHashMap<>();

  MethodProbeInserter(
      String owner,
      MethodNode method,
      int classId,
      ClassProbesBuilder probes,
      boolean initializer,
      boolean frames,
      boolean exceptionProbes) {
    this.owner = owner;
    this.method = method;
    this.classId = classId;
    this.probes = probes;
    this.initializer = initializer;
    this.frames = frames;
    this.exceptionProbes = exceptionProbes;
  }

  /**
   * @return {@code false} if the method was left unchanged
   */
  boolean instrument() {
    if (method.instructions.size() == 0) {
      return false;
    }
    insns = method.instructions.toArray();
    if (!readLines() || !buildBlocks()) {
      return false;
    }
    computeDominators();
    computeBlockLines();
    stackSizes = frames ? StackSizes.compute(insns) : null;

    int singleLine = singleLine();
    if (singleLine >= 0) {
      insertEntryProbe(singleLine);
    } else {
      for (int block = 0; block < blockCount; block++) {
        if (reachable[block]) {
          insertBlockProbes(block);
        }
      }
      List<TryCatchBlockNode> originalHandlers = new ArrayList<>(method.tryCatchBlocks);
      boolean constructor = "<init>".equals(method.name);
      if (exceptionProbes && (frames || !constructor)) {
        boolean[] uninitializedThis = constructor ? findUninitializedThis() : null;
        for (int block = 0; block < blockCount; block++) {
          if (reachable[block]) {
            insertExceptionProbes(block, uninitializedThis, originalHandlers);
          }
        }
      }
      List<TryCatchBlockNode> handlers = new ArrayList<>();
      for (TryCatchBlockNode handler : originalHandlers) {
        List<TryCatchBlockNode> recording = caughtHandlers.get(handler);
        if (recording != null) {
          handlers.addAll(recording);
        }
        handlers.add(handler);
      }
      handlers.addAll(uncaughtHandlers);
      method.tryCatchBlocks = handlers;
    }

    method.instructions.add(tail);
    method.maxStack = Math.max(method.maxStack + 2, 4);
    return true;
  }

  /**
   * An instruction belongs to the line of the last line table entry before it, so of several
   * entries at one offset only the last one counts.
   */
  private boolean readLines() {
    lineOf = new int[insns.length];
    int line = -1;
    boolean found = false;
    for (int i = 0; i < insns.length; i++) {
      AbstractInsnNode insn = insns[i];
      if (insn instanceof LineNumberNode) {
        line = ((LineNumberNode) insn).line;
        found = true;
      }
      int opcode = insn.getOpcode();
      if (opcode == JSR || opcode == RET) {
        return false;
      }
      lineOf[i] = line;
    }
    return found;
  }

  private boolean buildBlocks() {
    labelIndex = new IdentityHashMap<>();
    jumpTargets = Collections.newSetFromMap(new IdentityHashMap<>());
    for (int i = 0; i < insns.length; i++) {
      AbstractInsnNode insn = insns[i];
      if (insn instanceof LabelNode) {
        labelIndex.put((LabelNode) insn, i);
      } else if (insn instanceof JumpInsnNode) {
        jumpTargets.add(((JumpInsnNode) insn).label);
      } else if (insn instanceof TableSwitchInsnNode) {
        jumpTargets.add(((TableSwitchInsnNode) insn).dflt);
        jumpTargets.addAll(((TableSwitchInsnNode) insn).labels);
      } else if (insn instanceof LookupSwitchInsnNode) {
        jumpTargets.add(((LookupSwitchInsnNode) insn).dflt);
        jumpTargets.addAll(((LookupSwitchInsnNode) insn).labels);
      }
    }
    for (TryCatchBlockNode handler : method.tryCatchBlocks) {
      jumpTargets.add(handler.handler);
    }

    blockOf = new int[insns.length];
    Arrays.fill(blockOf, -1);
    int[] first = new int[16];
    int[] last = new int[16];
    int current = -1;
    boolean leader = true;
    for (int i = 0; i < insns.length; i++) {
      AbstractInsnNode insn = insns[i];
      if (insn instanceof LabelNode && jumpTargets.contains(insn)) {
        leader = true;
      }
      if (insn.getOpcode() < 0) {
        continue;
      }
      if (leader) {
        current++;
        if (current == first.length) {
          first = Arrays.copyOf(first, current * 2);
          last = Arrays.copyOf(last, current * 2);
        }
        first[current] = i;
        leader = false;
      }
      blockOf[i] = current;
      last[current] = i;
      if (endsBlock(insn)) {
        leader = true;
      }
    }
    blockCount = current + 1;
    if (blockCount == 0) {
      return false;
    }
    blockFirst = Arrays.copyOf(first, blockCount);
    blockLast = Arrays.copyOf(last, blockCount);

    successors = new int[blockCount][];
    for (int block = 0; block < blockCount; block++) {
      int[] next = successorsOf(block);
      for (int successor : next) {
        if (successor < 0 || successor >= blockCount) {
          return false;
        }
      }
      successors[block] = next;
    }
    return true;
  }

  private int[] successorsOf(int block) {
    AbstractInsnNode end = insns[blockLast[block]];
    int opcode = end.getOpcode();
    if (end instanceof JumpInsnNode) {
      int target = blockAt(((JumpInsnNode) end).label);
      return opcode == GOTO ? new int[] {target} : new int[] {target, block + 1};
    }
    if (end instanceof TableSwitchInsnNode) {
      TableSwitchInsnNode tableSwitch = (TableSwitchInsnNode) end;
      return switchTargets(tableSwitch.dflt, tableSwitch.labels);
    }
    if (end instanceof LookupSwitchInsnNode) {
      LookupSwitchInsnNode lookupSwitch = (LookupSwitchInsnNode) end;
      return switchTargets(lookupSwitch.dflt, lookupSwitch.labels);
    }
    if (isReturn(opcode) || opcode == ATHROW) {
      return new int[0];
    }
    return new int[] {block + 1};
  }

  private int[] switchTargets(LabelNode defaultLabel, List<LabelNode> labels) {
    int[] targets = new int[labels.size() + 1];
    int count = 0;
    targets[count++] = blockAt(defaultLabel);
    for (LabelNode label : labels) {
      int target = blockAt(label);
      boolean seen = false;
      for (int i = 0; i < count; i++) {
        seen |= targets[i] == target;
      }
      if (!seen) {
        targets[count++] = target;
      }
    }
    return Arrays.copyOf(targets, count);
  }

  private int blockAt(LabelNode label) {
    Integer index = labelIndex.get(label);
    if (index == null) {
      return -1;
    }
    int i = index;
    while (i < insns.length && insns[i].getOpcode() < 0) {
      i++;
    }
    return i < insns.length ? blockOf[i] : -1;
  }

  /** Iterative dominators (Cooper, Harvey, Kennedy) with a virtual root above every entry. */
  private void computeDominators() {
    root = blockCount;
    int[] entries = new int[method.tryCatchBlocks.size() + 1];
    int entryCount = 0;
    entries[entryCount++] = 0;
    for (TryCatchBlockNode handler : method.tryCatchBlocks) {
      int block = blockAt(handler.handler);
      boolean seen = false;
      for (int i = 0; i < entryCount; i++) {
        seen |= entries[i] == block;
      }
      if (!seen && block >= 0) {
        entries[entryCount++] = block;
      }
    }
    entries = Arrays.copyOf(entries, entryCount);

    int[][] predecessors = new int[blockCount + 1][];
    int[] predecessorCount = new int[blockCount + 1];
    for (int block = 0; block < blockCount; block++) {
      for (int successor : successors[block]) {
        predecessorCount[successor]++;
      }
    }
    for (int entry : entries) {
      predecessorCount[entry]++;
    }
    for (int block = 0; block <= blockCount; block++) {
      predecessors[block] = new int[predecessorCount[block]];
      predecessorCount[block] = 0;
    }
    for (int block = 0; block < blockCount; block++) {
      for (int successor : successors[block]) {
        predecessors[successor][predecessorCount[successor]++] = block;
      }
    }
    for (int entry : entries) {
      predecessors[entry][predecessorCount[entry]++] = root;
    }

    // postorder numbering with an explicit stack
    int[] postorder = new int[blockCount + 1];
    Arrays.fill(postorder, -1);
    int[] order = new int[blockCount + 1];
    int ordered = 0;
    boolean[] visited = new boolean[blockCount + 1];
    int[] stack = new int[blockCount + 1];
    int[] nextChild = new int[blockCount + 1];
    int depth = 0;
    stack[depth++] = root;
    visited[root] = true;
    while (depth > 0) {
      int node = stack[depth - 1];
      int[] children = node == root ? entries : successors[node];
      if (nextChild[node] < children.length) {
        int child = children[nextChild[node]++];
        if (!visited[child]) {
          visited[child] = true;
          stack[depth++] = child;
        }
      } else {
        depth--;
        postorder[node] = ordered;
        order[ordered++] = node;
      }
    }

    reachable = Arrays.copyOf(visited, blockCount);
    idom = new int[blockCount + 1];
    Arrays.fill(idom, -1);
    idom[root] = root;
    boolean changed = true;
    while (changed) {
      changed = false;
      for (int k = ordered - 2; k >= 0; k--) {
        int block = order[k];
        int newIdom = -1;
        for (int predecessor : predecessors[block]) {
          if (!visited[predecessor] || idom[predecessor] < 0) {
            continue;
          }
          newIdom = newIdom < 0 ? predecessor : intersect(predecessor, newIdom, postorder);
        }
        if (idom[block] != newIdom) {
          idom[block] = newIdom;
          changed = true;
        }
      }
    }
  }

  private int intersect(int a, int b, int[] postorder) {
    while (a != b) {
      while (postorder[a] < postorder[b]) {
        a = idom[a];
      }
      while (postorder[b] < postorder[a]) {
        b = idom[b];
      }
    }
    return a;
  }

  private void computeBlockLines() {
    blockLines = new int[blockCount][];
    blockNode = new int[blockCount];
    blockProbe = new int[blockCount];
    Arrays.fill(blockNode, -1);
    Arrays.fill(blockProbe, -1);
    LineList lines = new LineList();
    for (int block = 0; block < blockCount; block++) {
      lines.clear();
      for (int i = blockFirst[block]; i <= blockLast[block]; i++) {
        if (insns[i].getOpcode() >= 0) {
          addLinesAt(i, lines);
        }
      }
      blockLines[block] = lines.toArray();
    }
  }

  private void addLinesAt(int i, LineList lines) {
    lines.add(lineOf[i]);
  }

  private boolean startsNewLine(int i, LineList lines) {
    return lineOf[i] >= 0 && !lines.contains(lineOf[i]);
  }

  /** Line numbers in the order they were added, without duplicates. */
  private static final class LineList {
    private int[] lines = new int[8];
    private int count;

    void add(int line) {
      if (line < 0 || contains(line)) {
        return;
      }
      if (count == lines.length) {
        lines = Arrays.copyOf(lines, count * 2);
      }
      lines[count++] = line;
    }

    boolean contains(int line) {
      for (int k = 0; k < count; k++) {
        if (lines[k] == line) {
          return true;
        }
      }
      return false;
    }

    int size() {
      return count;
    }

    int[] toArray() {
      return Arrays.copyOf(lines, count);
    }

    void clear() {
      count = 0;
    }
  }

  /**
   * @return the line of a method whose reachable code is all on that line and starts with it, or -1
   */
  private int singleLine() {
    int line = -1;
    for (int block = 0; block < blockCount; block++) {
      if (!reachable[block]) {
        continue;
      }
      for (int blockLine : blockLines[block]) {
        if (line < 0) {
          line = blockLine;
        } else if (blockLine != line) {
          return -1;
        }
      }
    }
    return line >= 0 && lineOf[blockFirst[0]] == line ? line : -1;
  }

  private void insertEntryProbe(int line) {
    int probe = probes.newProbe(new int[] {line}, -1);
    LabelNode start = null;
    boolean hasFrame = false;
    for (int i = 0; i < blockFirst[0]; i++) {
      if (insns[i] instanceof LabelNode) {
        start = (LabelNode) insns[i];
      } else if (insns[i] instanceof FrameNode) {
        hasFrame = true;
      }
    }
    InsnList code = new InsnList();
    if (hasFrame && start != null) {
      code.add(readProbe(probe, IFNE, start));
      code.add(callRecorder(probe));
    } else {
      start = new LabelNode();
      code.add(readProbe(probe, IFNE, start));
      code.add(callRecorder(probe));
      code.add(start);
      if (frames) {
        code.add(entryFrame());
      }
    }
    method.instructions.insert(code);
  }

  private void insertBlockProbes(int block) {
    AbstractInsnNode end = insns[blockLast[block]];
    int opcode = end.getOpcode();
    if (isReturn(opcode)) {
      insertReturnProbe(blockLast[block], blockProbe(block));
    } else if (opcode == ATHROW) {
      // recorded by the exception probes of the block
    } else if (opcode == GOTO) {
      if (idom[successors[block][0]] != block) {
        InsnList code = readProbe(blockProbe(block), IFNE, ((JumpInsnNode) end).label);
        code.add(callRecorder(blockProbe(block)));
        method.instructions.insertBefore(end, code);
      }
    } else if (end instanceof JumpInsnNode) {
      JumpInsnNode jump = (JumpInsnNode) end;
      if (idom[block + 1] != block) {
        insertFallThroughProbe(block, blockProbe(block));
      }
      if (idom[blockAt(jump.label)] != block) {
        if (frames) {
          jump.label = edgeProbe(blockProbe(block), jump.label);
        } else {
          insertInvertedEdgeProbe(jump, blockProbe(block));
        }
      }
    } else if (end instanceof TableSwitchInsnNode) {
      TableSwitchInsnNode tableSwitch = (TableSwitchInsnNode) end;
      Map<LabelNode, LabelNode> edges = new IdentityHashMap<>();
      tableSwitch.dflt = switchEdge(block, tableSwitch.dflt, edges);
      for (int i = 0; i < tableSwitch.labels.size(); i++) {
        tableSwitch.labels.set(i, switchEdge(block, tableSwitch.labels.get(i), edges));
      }
    } else if (end instanceof LookupSwitchInsnNode) {
      LookupSwitchInsnNode lookupSwitch = (LookupSwitchInsnNode) end;
      Map<LabelNode, LabelNode> edges = new IdentityHashMap<>();
      lookupSwitch.dflt = switchEdge(block, lookupSwitch.dflt, edges);
      for (int i = 0; i < lookupSwitch.labels.size(); i++) {
        lookupSwitch.labels.set(i, switchEdge(block, lookupSwitch.labels.get(i), edges));
      }
    } else if (idom[block + 1] != block) {
      insertFallThroughProbe(block, blockProbe(block));
    }
  }

  private LabelNode switchEdge(int block, LabelNode target, Map<LabelNode, LabelNode> edges) {
    if (idom[blockAt(target)] == block) {
      return target;
    }
    LabelNode edge = edges.get(target);
    if (edge == null) {
      edge = edgeProbe(blockProbe(block), target);
      edges.put(target, edge);
    }
    return edge;
  }

  /** Probe on a jump edge, placed after the method body: records, then jumps to the target. */
  private LabelNode edgeProbe(int probe, LabelNode target) {
    LabelNode edge = new LabelNode();
    tail.add(edge);
    if (frames) {
      tail.add(copyFrame(frameAt(target)));
    }
    tail.add(readProbe(probe, IFNE, target));
    tail.add(callRecorder(probe));
    tail.add(new JumpInsnNode(GOTO, target));
    return edge;
  }

  /**
   * Without stack map frames, the probe on a jump edge stays next to the jump, which is inverted to
   * skip it, so no jump changes direction.
   */
  private void insertInvertedEdgeProbe(JumpInsnNode jump, int probe) {
    LabelNode target = jump.label;
    LabelNode fallThrough = new LabelNode();
    jump.setOpcode(invert(jump.getOpcode()));
    jump.label = fallThrough;
    InsnList code = readProbe(probe, IFNE, target);
    code.add(callRecorder(probe));
    code.add(new JumpInsnNode(GOTO, target));
    code.add(fallThrough);
    method.instructions.insert(jump, code);
  }

  private static int invert(int opcode) {
    if (opcode == IFNULL) {
      return IFNONNULL;
    } else if (opcode == IFNONNULL) {
      return IFNULL;
    }
    // IFEQ to IF_ACMPNE come in pairs of opposite conditions
    return ((opcode - IFEQ) ^ 1) + IFEQ;
  }

  /** Probe at the end of a block that falls through into a block it does not dominate. */
  private void insertFallThroughProbe(int block, int probe) {
    LabelNode target = null;
    for (int i = blockLast[block] + 1; i < blockFirst[block + 1]; i++) {
      if (insns[i] instanceof LabelNode && jumpTargets.contains(insns[i])) {
        target = (LabelNode) insns[i];
      }
    }
    if (target == null) {
      if (frames) {
        throw new IllegalStateException("No jump target before a merge block in " + method.name);
      }
      target = new LabelNode();
      method.instructions.insertBefore(insns[blockFirst[block + 1]], target);
    }
    InsnList code = readProbe(probe, IFNE, target);
    code.add(callRecorder(probe));
    method.instructions.insert(insns[blockLast[block]], code);
  }

  /**
   * The slow path of a probe before a return is a copy of the return after the method body. Its
   * frame holds only the return value, so where the stack holds more the probe calls the recorder
   * unconditionally instead.
   */
  private void insertReturnProbe(int returnIndex, int probe) {
    AbstractInsnNode returnInsn = insns[returnIndex];
    if (frames && stackSizes[returnIndex] != Type.getReturnType(method.desc).getSize()) {
      method.instructions.insertBefore(returnInsn, callRecorder(probe, true));
      return;
    }
    LabelNode slowPath = new LabelNode();
    method.instructions.insertBefore(returnInsn, readProbe(probe, IFEQ, slowPath));
    tail.add(slowPath);
    if (frames) {
      tail.add(returnFrame(returnInsn.getOpcode()));
    }
    tail.add(callRecorder(probe));
    tail.add(new InsnNode(returnInsn.getOpcode()));
  }

  private void insertExceptionProbes(
      int block, boolean[] uninitializedThis, List<TryCatchBlockNode> originalHandlers) {
    LineList lines = new LineList();
    int segmentFirst = -1;
    int segmentLast = -1;
    boolean segmentThrows = false;
    for (int i = blockFirst[block]; i <= blockLast[block]; i++) {
      AbstractInsnNode insn = insns[i];
      if (insn.getOpcode() < 0) {
        continue;
      }
      if (startsNewLine(i, lines)) {
        if (segmentThrows) {
          addSegment(block, segmentFirst, segmentLast, lines, originalHandlers);
        }
        addLinesAt(i, lines);
        segmentFirst = -1;
        segmentThrows = false;
      }
      if (uninitializedThis != null && uninitializedThis[i]) {
        continue; // no handler may cover code that runs before this is initialized
      }
      if (segmentFirst < 0) {
        segmentFirst = i;
      }
      segmentLast = i;
      segmentThrows |= mayThrow(insn);
    }
    if (segmentThrows) {
      addSegment(block, segmentFirst, segmentLast, lines, originalHandlers);
    }
  }

  /**
   * Records, when an exception leaves instructions {@code first} to {@code last}, the lines the
   * block has started so far.
   */
  private void addSegment(
      int block, int first, int last, LineList lines, List<TryCatchBlockNode> originalHandlers) {
    int probe =
        lines.size() == blockLines[block].length
            ? blockProbe(block)
            : probes.newProbe(lines.toArray(), parentNode(block));

    LabelNode start = new LabelNode();
    LabelNode end = new LabelNode();
    method.instructions.insertBefore(insns[first], start);
    method.instructions.insert(insns[last], end);

    LabelNode uncaught = new LabelNode();
    tail.add(uncaught);
    if (frames) {
      tail.add(new FrameNode(F_NEW, 0, new Object[0], 1, new Object[] {"java/lang/Throwable"}));
    }
    tail.add(callRecorder(probe));
    tail.add(new InsnNode(ATHROW));
    uncaughtHandlers.add(new TryCatchBlockNode(start, end, uncaught, null));

    for (TryCatchBlockNode handler : originalHandlers) {
      int handlerStart = labelIndex.get(handler.start);
      int handlerEnd = labelIndex.get(handler.end);
      int low = Math.max(first, handlerStart + 1);
      int high = Math.min(last, handlerEnd - 1);
      boolean throwsInside = false;
      for (int i = low; i <= high && !throwsInside; i++) {
        throwsInside = insns[i].getOpcode() >= 0 && mayThrow(insns[i]);
      }
      if (!throwsInside) {
        continue;
      }
      LabelNode rangeStart = first > handlerStart ? start : handler.start;
      LabelNode rangeEnd = last < handlerEnd ? end : handler.end;
      caughtHandlers
          .computeIfAbsent(handler, h -> new ArrayList<>())
          .add(
              new TryCatchBlockNode(
                  rangeStart, rangeEnd, trampoline(probe, handler.handler), handler.type));
    }
  }

  /** Records a probe and continues to an original exception handler. */
  private LabelNode trampoline(int probe, LabelNode handler) {
    LabelNode trampoline = new LabelNode();
    tail.add(trampoline);
    if (frames) {
      tail.add(copyFrame(frameAt(handler)));
    }
    tail.add(callRecorder(probe));
    tail.add(new JumpInsnNode(GOTO, handler));
    return trampoline;
  }

  private int blockProbe(int block) {
    if (blockProbe[block] < 0) {
      blockProbe[block] = probes.newProbe(blockLines[block], parentNode(block));
    }
    return blockProbe[block];
  }

  private int parentNode(int block) {
    int dominator = idom[block];
    return dominator == root || dominator < 0 ? -1 : node(dominator);
  }

  private int node(int block) {
    if (blockNode[block] >= 0) {
      return blockNode[block];
    }
    int[] chain = new int[8];
    int length = 0;
    int current = block;
    while (current != root && current >= 0 && blockNode[current] < 0) {
      if (length == chain.length) {
        chain = Arrays.copyOf(chain, length * 2);
      }
      chain[length++] = current;
      current = idom[current];
    }
    int parent = current == root || current < 0 ? -1 : blockNode[current];
    for (int k = length - 1; k >= 0; k--) {
      int link = chain[k];
      blockNode[link] = probes.newNode(blockLines[link], parent);
      parent = blockNode[link];
    }
    return blockNode[block];
  }

  /**
   * Marks the instructions of a constructor that run while {@code this} is uninitialized. No
   * exception handler may cover them. Frames give the state wherever control flow merges, and the
   * call that initializes {@code this} changes it.
   */
  private boolean[] findUninitializedThis() {
    boolean[] uninitialized = new boolean[insns.length];
    boolean thisUninitialized = true;
    int pendingNew = 0;
    for (int i = 0; i < insns.length; i++) {
      AbstractInsnNode insn = insns[i];
      if (insn instanceof FrameNode) {
        FrameNode frame = (FrameNode) insn;
        thisUninitialized = frame.local != null && frame.local.contains(UNINITIALIZED_THIS);
        pendingNew = 0;
        if (frame.stack != null) {
          for (Object type : frame.stack) {
            if (type instanceof LabelNode) {
              pendingNew++; // an object created by NEW and not yet initialized
            }
          }
        }
      }
      uninitialized[i] = thisUninitialized;
      int opcode = insn.getOpcode();
      if (opcode == NEW) {
        pendingNew++;
      } else if (opcode == INVOKESPECIAL && "<init>".equals(((MethodInsnNode) insn).name)) {
        if (pendingNew > 0) {
          pendingNew--;
        } else {
          thisUninitialized = false;
        }
      }
    }
    return uninitialized;
  }

  private InsnList readProbe(int probe, int jumpOpcode, LabelNode target) {
    InsnList code = new InsnList();
    code.add(new FieldInsnNode(GETSTATIC, RECORDER, "PROBES", "[[Z"));
    code.add(push(classId));
    code.add(new InsnNode(AALOAD));
    code.add(push(probe));
    code.add(new InsnNode(BALOAD));
    code.add(new JumpInsnNode(jumpOpcode, target));
    return code;
  }

  private InsnList callRecorder(int probe) {
    return callRecorder(probe, false);
  }

  /**
   * @param unconditional whether the call is made on every execution, so the recorder must check
   *     the slot itself
   */
  private InsnList callRecorder(int probe, boolean unconditional) {
    InsnList code = new InsnList();
    code.add(push(classId));
    code.add(push(probe));
    String name = initializer ? "recordInit" : unconditional ? "hit" : "record";
    code.add(new MethodInsnNode(INVOKESTATIC, RECORDER, name, "(II)V", false));
    return code;
  }

  private static AbstractInsnNode push(int value) {
    if (value <= 5) {
      return new InsnNode(ICONST_0 + value);
    } else if (value <= Byte.MAX_VALUE) {
      return new IntInsnNode(BIPUSH, value);
    } else if (value <= Short.MAX_VALUE) {
      return new IntInsnNode(SIPUSH, value);
    }
    return new LdcInsnNode(value);
  }

  private FrameNode frameAt(LabelNode label) {
    for (int i = labelIndex.get(label); i < insns.length; i++) {
      if (insns[i] instanceof FrameNode) {
        return (FrameNode) insns[i];
      }
      if (insns[i].getOpcode() >= 0) {
        break;
      }
    }
    throw new IllegalStateException("No stack map frame at a jump target in " + method.name);
  }

  private static FrameNode copyFrame(FrameNode frame) {
    Object[] locals = frame.local != null ? frame.local.toArray() : new Object[0];
    Object[] stack = frame.stack != null ? frame.stack.toArray() : new Object[0];
    return new FrameNode(F_NEW, locals.length, locals, stack.length, stack);
  }

  private FrameNode entryFrame() {
    List<Object> locals = new ArrayList<>();
    if ((method.access & ACC_STATIC) == 0) {
      locals.add("<init>".equals(method.name) ? UNINITIALIZED_THIS : owner);
    }
    for (Type argument : Type.getArgumentTypes(method.desc)) {
      locals.add(frameType(argument));
    }
    return new FrameNode(F_NEW, locals.size(), locals.toArray(), 0, new Object[0]);
  }

  private FrameNode returnFrame(int opcode) {
    Object[] stack;
    switch (opcode) {
      case IRETURN:
        stack = new Object[] {Opcodes.INTEGER};
        break;
      case LRETURN:
        stack = new Object[] {Opcodes.LONG};
        break;
      case FRETURN:
        stack = new Object[] {Opcodes.FLOAT};
        break;
      case DRETURN:
        stack = new Object[] {Opcodes.DOUBLE};
        break;
      case ARETURN:
        stack = new Object[] {frameType(Type.getReturnType(method.desc))};
        break;
      default:
        stack = new Object[0];
    }
    return new FrameNode(F_NEW, 0, new Object[0], stack.length, stack);
  }

  private static Object frameType(Type type) {
    switch (type.getSort()) {
      case Type.BOOLEAN:
      case Type.CHAR:
      case Type.BYTE:
      case Type.SHORT:
      case Type.INT:
        return Opcodes.INTEGER;
      case Type.FLOAT:
        return Opcodes.FLOAT;
      case Type.LONG:
        return Opcodes.LONG;
      case Type.DOUBLE:
        return Opcodes.DOUBLE;
      case Type.ARRAY:
        return type.getDescriptor();
      default:
        return type.getInternalName();
    }
  }

  private static boolean endsBlock(AbstractInsnNode insn) {
    int opcode = insn.getOpcode();
    return insn instanceof JumpInsnNode
        || insn instanceof TableSwitchInsnNode
        || insn instanceof LookupSwitchInsnNode
        || isReturn(opcode)
        || opcode == ATHROW;
  }

  private static boolean isReturn(int opcode) {
    return opcode >= IRETURN && opcode <= RETURN;
  }

  /** Whether an instruction can raise an exception, leaving its block midway. */
  static boolean mayThrow(AbstractInsnNode insn) {
    int opcode = insn.getOpcode();
    switch (opcode) {
      case Opcodes.IDIV:
      case Opcodes.LDIV:
      case Opcodes.IREM:
      case Opcodes.LREM:
        return true;
      case Opcodes.LDC:
        Object constant = ((LdcInsnNode) insn).cst;
        return !(constant instanceof Number || constant instanceof String);
      default:
        break;
    }
    if (opcode <= Opcodes.SALOAD) {
      // constants and local variable loads; array loads start at IALOAD
      return opcode >= Opcodes.IALOAD;
    }
    if (opcode <= Opcodes.SASTORE) {
      // stores to locals are safe; array stores may throw
      return opcode >= Opcodes.IASTORE;
    }
    if (opcode <= Opcodes.RETURN) {
      // stack, arithmetic, conversion, comparison, jump, switch and return instructions
      return false;
    }
    return opcode != Opcodes.IFNULL && opcode != Opcodes.IFNONNULL;
  }
}
