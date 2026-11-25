package datadog.trace.civisibility.coverage.probe;

import static datadog.trace.civisibility.coverage.probe.StackMapFrames.DOUBLE;
import static datadog.trace.civisibility.coverage.probe.StackMapFrames.FLOAT;
import static datadog.trace.civisibility.coverage.probe.StackMapFrames.INTEGER;
import static datadog.trace.civisibility.coverage.probe.StackMapFrames.LONG;
import static datadog.trace.civisibility.coverage.probe.StackMapFrames.NONE;
import static datadog.trace.civisibility.coverage.probe.StackMapFrames.NULL;
import static datadog.trace.civisibility.coverage.probe.StackMapFrames.OBJECT_TAG;
import static datadog.trace.civisibility.coverage.probe.StackMapFrames.TOP;
import static datadog.trace.civisibility.coverage.probe.StackMapFrames.UNINITIALIZED_TAG;
import static datadog.trace.civisibility.coverage.probe.StackMapFrames.UNINITIALIZED_THIS;
import static datadog.trace.civisibility.coverage.probe.UnsupportedClassException.CODE_TOO_LONG;
import static datadog.trace.civisibility.coverage.probe.UnsupportedClassException.SAME_FRAME_OFFSET;
import static org.objectweb.asm.Opcodes.AALOAD;
import static org.objectweb.asm.Opcodes.ACC_STATIC;
import static org.objectweb.asm.Opcodes.ALOAD;
import static org.objectweb.asm.Opcodes.ARETURN;
import static org.objectweb.asm.Opcodes.ASTORE;
import static org.objectweb.asm.Opcodes.ATHROW;
import static org.objectweb.asm.Opcodes.BALOAD;
import static org.objectweb.asm.Opcodes.DRETURN;
import static org.objectweb.asm.Opcodes.FRETURN;
import static org.objectweb.asm.Opcodes.GETSTATIC;
import static org.objectweb.asm.Opcodes.GOTO;
import static org.objectweb.asm.Opcodes.IALOAD;
import static org.objectweb.asm.Opcodes.IASTORE;
import static org.objectweb.asm.Opcodes.ICONST_0;
import static org.objectweb.asm.Opcodes.IDIV;
import static org.objectweb.asm.Opcodes.IFEQ;
import static org.objectweb.asm.Opcodes.IFNE;
import static org.objectweb.asm.Opcodes.IFNONNULL;
import static org.objectweb.asm.Opcodes.IFNULL;
import static org.objectweb.asm.Opcodes.ILOAD;
import static org.objectweb.asm.Opcodes.INVOKESPECIAL;
import static org.objectweb.asm.Opcodes.INVOKESTATIC;
import static org.objectweb.asm.Opcodes.IOR;
import static org.objectweb.asm.Opcodes.IREM;
import static org.objectweb.asm.Opcodes.IRETURN;
import static org.objectweb.asm.Opcodes.ISHL;
import static org.objectweb.asm.Opcodes.ISTORE;
import static org.objectweb.asm.Opcodes.LDC;
import static org.objectweb.asm.Opcodes.LDIV;
import static org.objectweb.asm.Opcodes.LOOKUPSWITCH;
import static org.objectweb.asm.Opcodes.LREM;
import static org.objectweb.asm.Opcodes.LRETURN;
import static org.objectweb.asm.Opcodes.MONITORENTER;
import static org.objectweb.asm.Opcodes.MONITOREXIT;
import static org.objectweb.asm.Opcodes.NEW;
import static org.objectweb.asm.Opcodes.RETURN;
import static org.objectweb.asm.Opcodes.SALOAD;
import static org.objectweb.asm.Opcodes.SASTORE;
import static org.objectweb.asm.Opcodes.TABLESWITCH;

import datadog.trace.civisibility.coverage.probe.MethodProbeInserter.ProbePlacementException;
import java.util.Arrays;

/**
 * Inserts line probes into one method of a class file, placed as {@link MethodProbeInserter} places
 * them, without ASM. The method's own instructions are copied byte for byte with the probe code
 * between them; only jump offsets, switch padding, the exception table, the line number and local
 * variable tables and the stack map frames are rewritten, every frame as a full frame.
 *
 * <p>Code is inserted around each original instruction in this order: the position its label stands
 * for, where jumps, handlers, lines and frames at the instruction point; code inserted before it;
 * the instruction; code inserted after it. Code placed after the method body follows the original
 * code. This is the order {@link MethodProbeInserter} leaves in ASM's instruction list, so both
 * produce the same code.
 */
final class CodePatcher {

  private final ClassFilePatcher patch;
  private final MethodCode code;
  private final int classId;
  private final ClassProbesBuilder probes;
  private final boolean initializer;
  private final boolean exceptionProbes;

  /** The number of original instructions. */
  private final int count;

  private int maxLocals;
  private int[] lineOf;
  private boolean[] jumpTarget;
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

  /** The local variable holding the method's probe array, or -1 if the method has none. */
  private int probesLocal = -1;

  /** The local variable holding the probe of the last code started that can throw, or -1. */
  private int progressLocal = -1;

  /** The stack map frame at each original instruction, as full frames; null where there is none. */
  private int[][] frameLocals;

  private int[][] frameStack;
  private int[] entryLocals;

  /** Whether a handler covers code in its own first block. */
  private boolean handlerCoversItself;

  /** Monitors held before each instruction, -1 where no path reaches it; null without monitors. */
  private int[] monitorDepths;

  private boolean monitorsBalanced = true;

  private InsertedCode prologue;
  private InsertedCode[] before;
  private InsertedCode[] after;
  private final InsertedCode tail = new InsertedCode();
  private InsertedCode thrown;

  /**
   * Labels 0 to {@code count} stand for the label of each original instruction, then for the end of
   * the original code. Labels of inserted code follow.
   */
  private int labelCount;

  private int[] labelOffset;

  /** The stack map frames at inserted labels, by label minus {@code count + 1}; null for none. */
  private int[][] insertedFrameLocals = new int[8][];

  private int[][] insertedFrameStack = new int[8][];

  /** The label each original jump goes to, -1 for other instructions. */
  private int[] jumpLabel;

  /** The labels each original switch goes to, its default first. */
  private int[][] switchLabels;

  /**
   * Exception table entries, in table order. Their ranges are positions: {@code 8 * i} for the
   * label of original instruction {@code i}, {@code 8 * i + 2} for right before the instruction,
   * {@code 8 * i + 4} for right after it, {@code -1 - label} for an inserted label. Positions of
   * original instructions compare in code order; code inserted before instruction {@code i} lies
   * between {@code 8 * i} and {@code 8 * i + 2}.
   */
  private int entryCount;

  private int[] entryStart = new int[8];
  private int[] entryEnd = new int[8];
  private int[] entryHandler = new int[8];
  private int[] entryType = new int[8];

  /** The original handler an entry is, or -1 for an inserted entry. */
  private int[] entryOriginal = new int[8];

  /** Probes placed after the method body on a jump edge: their labels, the jump and its target. */
  private int edgeCount;

  private int[] edgeStart = new int[8];
  private int[] edgeEnd = new int[8];
  private int[] edgeJump = new int[8];
  private int[] edgeTarget = new int[8];

  private int[] instructionOffset;
  private int[] instructionEnd;
  private int thrownOffset;
  private int codeLength;

  CodePatcher(
      ClassFilePatcher patch,
      MethodCode code,
      int classId,
      ClassProbesBuilder probes,
      boolean initializer,
      boolean exceptionProbes) {
    this.patch = patch;
    this.code = code;
    this.classId = classId;
    this.probes = probes;
    this.initializer = initializer;
    this.exceptionProbes = exceptionProbes;
    this.count = code.count;
    this.maxLocals = code.maxLocals;
  }

  /**
   * @return {@code false} if the method is to be left unchanged
   */
  boolean instrument() {
    if (!readLines() || !buildBlocks()) {
      return false;
    }
    readFrames();
    computeDominators();
    computeBlockLines();
    analyzeHandlers();
    computeStackSizes();
    initLabels();

    int singleLine = singleLine();
    if (singleLine >= 0) {
      insertEntryProbe(singleLine);
      return true;
    }
    if (!initializer) {
      probesLocal = addLocal(patch.probesType());
    }
    if (exceptionProbes) {
      progressLocal = addLocal(INTEGER);
    }
    for (int block = 0; block < blockCount; block++) {
      if (reachable[block]) {
        insertBlockProbes(block);
      }
    }
    if (exceptionProbes) {
      boolean[] uninitializedThis = "<init>".equals(code.name) ? findUninitializedThis() : null;
      int[] recordings = handlerRecordings();
      for (int block = 0; block < blockCount; block++) {
        if (reachable[block]) {
          insertProgressStores(block, uninitializedThis, recordings[block]);
        }
      }
      addThrownHandler(uninitializedThis);
    }
    coverEdgeProbes();
    // before the first label, so that no jump or exception handler reaches it
    prologue = new InsertedCode();
    if (probesLocal >= 0) {
      takeProbes(prologue);
      prologue.variable(ASTORE, probesLocal);
    }
    if (progressLocal >= 0) {
      prologue.opcode(ICONST_0);
      prologue.variable(ISTORE, progressLocal);
    }
    return true;
  }

  private boolean readLines() {
    if (code.hasSubroutines || !code.hasLines) {
      return false;
    }
    lineOf = new int[count];
    int line = -1;
    for (int i = 0; i < count; i++) {
      if (code.line[i] >= 0) {
        line = code.line[i];
      }
      lineOf[i] = line;
    }
    return true;
  }

  private boolean buildBlocks() {
    jumpTarget = new boolean[count];
    for (int i = 0; i < count; i++) {
      if (code.target[i] >= 0) {
        jumpTarget[code.target[i]] = true;
      } else if (code.switchTargets[i] != null) {
        for (int target : code.switchTargets[i]) {
          jumpTarget[target] = true;
        }
      }
    }
    for (int h = 0; h < code.handlerCount; h++) {
      jumpTarget[code.handlerEntry[h]] = true;
    }

    blockOf = new int[count];
    int[] first = new int[16];
    int[] last = new int[16];
    int current = -1;
    boolean leader = true;
    for (int i = 0; i < count; i++) {
      if (leader || jumpTarget[i]) {
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
      if (endsBlock(i)) {
        leader = true;
      }
    }
    blockCount = current + 1;
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

  private boolean endsBlock(int i) {
    int opcode = code.opcode[i];
    return code.target[i] >= 0
        || code.switchTargets[i] != null
        || isReturn(opcode)
        || opcode == ATHROW;
  }

  private int[] successorsOf(int block) {
    int end = blockLast[block];
    int opcode = code.opcode[end];
    if (code.target[end] >= 0) {
      int target = blockOf[code.target[end]];
      return opcode == GOTO ? new int[] {target} : new int[] {target, block + 1};
    }
    if (code.switchTargets[end] != null) {
      int[] targets = code.switchTargets[end];
      int[] blocks = new int[targets.length];
      int distinct = 0;
      for (int target : targets) {
        int targetBlock = blockOf[target];
        boolean seen = false;
        for (int k = 0; k < distinct; k++) {
          seen |= blocks[k] == targetBlock;
        }
        if (!seen) {
          blocks[distinct++] = targetBlock;
        }
      }
      return Arrays.copyOf(blocks, distinct);
    }
    if (isReturn(opcode) || opcode == ATHROW) {
      return new int[0];
    }
    return new int[] {block + 1};
  }

  private void readFrames() {
    frameLocals = new int[count][];
    frameStack = new int[count][];
    if (code.stackMapTable >= 0) {
      StackMapFrames.read(code, entryLocals(), frameLocals, frameStack);
    }
  }

  /** Iterative dominators (Cooper, Harvey, Kennedy) with a virtual root above every entry. */
  private void computeDominators() {
    root = blockCount;
    int[] entries = new int[code.handlerCount + 1];
    int entryTotal = 0;
    entries[entryTotal++] = 0;
    for (int h = 0; h < code.handlerCount; h++) {
      int block = blockOf[code.handlerEntry[h]];
      boolean seen = false;
      for (int k = 0; k < entryTotal; k++) {
        seen |= entries[k] == block;
      }
      if (!seen) {
        entries[entryTotal++] = block;
      }
    }
    entries = Arrays.copyOf(entries, entryTotal);

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
        lines.add(lineOf[i]);
      }
      blockLines[block] = lines.toArray();
    }
  }

  /** Finds whether a handler covers its own first block and, with monitors, the monitors held. */
  private void analyzeHandlers() {
    for (int h = 0; h < code.handlerCount; h++) {
      int entry = code.handlerEntry[h];
      for (int i = entry; i <= blockLast[blockOf[entry]]; i++) {
        handlerCoversItself |= covers(h, i);
      }
    }
    for (int i = 0; i < count; i++) {
      if (code.opcode[i] == MONITORENTER) {
        computeMonitorDepths();
        return;
      }
    }
  }

  private boolean covers(int h, int i) {
    return code.handlerStart[h] <= i && i < code.handlerEnd[h];
  }

  private boolean catchesAll(int h) {
    return code.handlerType[h] == 0;
  }

  /**
   * Follows every path through the method, exceptional ones included, counting the monitors held,
   * as HotSpot does before it compiles a method that enters monitors.
   */
  private void computeMonitorDepths() {
    int[] depths = new int[count];
    Arrays.fill(depths, -1);
    // pairs of an instruction index and the monitors held before it
    int[] work = new int[32];
    int size = 0;
    work[size++] = 0;
    work[size++] = 0;
    int[] next = new int[8];
    while (size > 0) {
      int depth = work[--size];
      int i = work[--size];
      if (i >= count) {
        continue;
      }
      if (depths[i] >= 0) {
        monitorsBalanced &= depths[i] == depth;
        continue;
      }
      depths[i] = depth;
      int opcode = code.opcode[i];
      int nextCount = 0;
      if (canTrap(i, depth)) {
        boolean caught = false;
        for (int h = 0; h < code.handlerCount && !caught; h++) {
          if (covers(h, i)) {
            next = set(next, nextCount++, code.handlerEntry[h]);
            caught = catchesAll(h);
          }
        }
        monitorsBalanced &= caught || depth == 0;
      }
      int depthAfter = depth;
      if (opcode == MONITORENTER) {
        depthAfter = depth + 1;
      } else if (opcode == MONITOREXIT) {
        monitorsBalanced &= depth > 0;
        depthAfter = Math.max(depth - 1, 0);
      } else if (isReturn(opcode)) {
        monitorsBalanced &= depth == 0;
      }
      int handlerTargets = nextCount;
      if (code.target[i] >= 0) {
        next = set(next, nextCount++, code.target[i]);
        if (opcode != GOTO) {
          next = set(next, nextCount++, i + 1);
        }
      } else if (code.switchTargets[i] != null) {
        for (int target : code.switchTargets[i]) {
          next = set(next, nextCount++, target);
        }
      } else if (!isReturn(opcode) && opcode != ATHROW) {
        next = set(next, nextCount++, i + 1);
      }
      if (size + 2 * nextCount > work.length) {
        work = Arrays.copyOf(work, Math.max(work.length * 2, size + 2 * nextCount));
      }
      for (int k = 0; k < nextCount; k++) {
        work[size++] = next[k];
        work[size++] = k < handlerTargets ? depth : depthAfter;
      }
    }
    monitorDepths = depths;
  }

  private static int[] set(int[] values, int index, int value) {
    if (index == values.length) {
      values = Arrays.copyOf(values, index * 2);
    }
    values[index] = value;
    return values;
  }

  /** Whether HotSpot takes an instruction to possibly throw, given the monitors held before it. */
  private boolean canTrap(int i, int depth) {
    int opcode = code.opcode[i];
    if (opcode == LDC) {
      return true;
    } else if (opcode == MONITOREXIT) {
      return depth == 0;
    } else if (isReturn(opcode)) {
      return depth > 0;
    }
    return mayThrow(i);
  }

  /** Whether an instruction can raise an exception, leaving its block midway. */
  private boolean mayThrow(int i) {
    int opcode = code.opcode[i];
    switch (opcode) {
      case IDIV:
      case LDIV:
      case IREM:
      case LREM:
        return true;
      case LDC:
        int tag = code.ldcConstantTag(i);
        return tag != ClassFile.CONSTANT_INTEGER
            && tag != ClassFile.CONSTANT_FLOAT
            && tag != ClassFile.CONSTANT_LONG
            && tag != ClassFile.CONSTANT_DOUBLE
            && tag != ClassFile.CONSTANT_STRING;
      default:
        break;
    }
    if (opcode <= SALOAD) {
      // constants and local variable loads; array loads start at IALOAD
      return opcode >= IALOAD;
    }
    if (opcode <= SASTORE) {
      // stores to locals are safe; array stores may throw
      return opcode >= IASTORE;
    }
    if (opcode <= RETURN) {
      // stack, arithmetic, conversion, comparison, jump, switch and return instructions
      return false;
    }
    return opcode != IFNULL && opcode != IFNONNULL;
  }

  /** Monitors held before instruction {@code i}: -1 if no path reaches it. */
  private int depthAt(int i) {
    return monitorDepths != null ? monitorDepths[i] : 0;
  }

  /** Monitors held right after instruction {@code i}. */
  private int depthAfter(int i) {
    int depth = depthAt(i);
    int opcode = code.opcode[i];
    if (depth < 0) {
      return depth;
    } else if (opcode == MONITORENTER) {
      return depth + 1;
    } else if (opcode == MONITOREXIT) {
      return Math.max(depth - 1, 0);
    }
    return depth;
  }

  /** Monitors held when original handler {@code h} is entered: -1 if no exception reaches it. */
  private int handlerDepth(int h) {
    return depthAt(code.handlerEntry[h]);
  }

  /**
   * Whether code inserted next to instruction {@code i}, with {@code depth} monitors held, may
   * throw: the handlers it would reach are entered with that many monitors held and none of them
   * starts with the block of {@code i}, and none is held if the exception can leave the method.
   */
  private boolean throwAllowedAt(int i, int depth) {
    if (!monitorsBalanced || (monitorDepths == null && !handlerCoversItself)) {
      return true;
    }
    if (depth < 0) {
      return false;
    }
    for (int h = 0; h < code.handlerCount; h++) {
      if (covers(h, i)) {
        if (handlerDepth(h) != depth || blockOf[code.handlerEntry[h]] == blockOf[i]) {
          return false;
        }
        if (catchesAll(h)) {
          return true;
        }
      }
    }
    return depth == 0;
  }

  private void requireThrowAllowed(int i, int depth) {
    if (!throwAllowedAt(i, depth)) {
      throw new ProbePlacementException(code.name, code.descriptor);
    }
  }

  /**
   * Whether code placed after the method body, with {@code depth} monitors held, may throw under
   * the first {@code size} of these exception table entries.
   */
  private boolean throwAllowedUnder(int[] entries, int size, int depth) {
    if (!monitorsBalanced || monitorDepths == null) {
      return true;
    }
    for (int k = 0; k < size; k++) {
      int entry = entries[k];
      int h = entryOriginal[entry];
      // the handler recording uncaught exceptions covers only code where no monitor is held
      int handlerDepth = h >= 0 ? handlerDepth(h) : 0;
      if (handlerDepth != depth) {
        return false;
      }
      if (entryType[entry] == 0) {
        return true;
      }
    }
    return depth == 0;
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
    prologue = new InsertedCode();
    if (frameLocals[0] != null) {
      readProbe(prologue, probe, IFNE, 0);
      callRecorder(prologue, probe, false);
    } else {
      int start = newLabel(entryLocals(), NONE);
      readProbe(prologue, probe, IFNE, start);
      callRecorder(prologue, probe, false);
      prologue.label(start);
    }
  }

  private void insertBlockProbes(int block) {
    int last = blockLast[block];
    int opcode = code.opcode[last];
    if (isReturn(opcode)) {
      requireThrowAllowed(last, depthAt(last));
      insertReturnProbe(last, blockProbe(block));
    } else if (opcode == ATHROW) {
      // recorded by the exception probes of the block
    } else if (opcode == GOTO) {
      if (idom[successors[block][0]] != block) {
        requireThrowAllowed(last, depthAt(last));
        int probe = blockProbe(block);
        InsertedCode inserted = before(last);
        readProbe(inserted, probe, IFNE, jumpLabel[last]);
        callRecorder(inserted, probe, false);
      }
    } else if (code.target[last] >= 0) {
      if (idom[block + 1] != block) {
        requireThrowAllowed(last, depthAt(last));
        insertFallThroughProbe(block, blockProbe(block));
      }
      if (idom[blockOf[code.target[last]]] != block) {
        jumpLabel[last] = edgeProbe(blockProbe(block), last, code.target[last]);
      }
    } else if (code.switchTargets[last] != null) {
      int[] targets = code.switchTargets[last];
      int[] labels = switchLabels[last];
      int[] edgeTargets = new int[targets.length];
      int[] edgeLabels = new int[targets.length];
      int edges = 0;
      for (int k = 0; k < targets.length; k++) {
        int target = targets[k];
        if (idom[blockOf[target]] == block) {
          continue;
        }
        int edge = -1;
        for (int e = 0; e < edges && edge < 0; e++) {
          if (edgeTargets[e] == target) {
            edge = edgeLabels[e];
          }
        }
        if (edge < 0) {
          edge = edgeProbe(blockProbe(block), last, target);
          edgeTargets[edges] = target;
          edgeLabels[edges++] = edge;
        }
        labels[k] = edge;
      }
    } else if (idom[block + 1] != block) {
      requireThrowAllowed(last, depthAfter(last));
      insertFallThroughProbe(block, blockProbe(block));
    }
  }

  /**
   * Probe on a jump edge, placed after the method body: records, then jumps to the target.
   *
   * @param jump the index of the jump
   * @return the label of the probe
   */
  private int edgeProbe(int probe, int jump, int target) {
    if (frameLocals[target] == null) {
      throw new IllegalStateException("No stack map frame at a jump target in " + code.name);
    }
    int edge = newLabel(frameLocals[target], frameStack[target]);
    int end = newLabel(null, null);
    tail.label(edge);
    readProbe(tail, probe, IFNE, target);
    callRecorder(tail, probe, false);
    tail.jump(GOTO, target);
    tail.label(end);
    if (edgeCount == edgeStart.length) {
      int length = edgeCount * 2;
      edgeStart = Arrays.copyOf(edgeStart, length);
      edgeEnd = Arrays.copyOf(edgeEnd, length);
      edgeJump = Arrays.copyOf(edgeJump, length);
      edgeTarget = Arrays.copyOf(edgeTarget, length);
    }
    edgeStart[edgeCount] = edge;
    edgeEnd[edgeCount] = end;
    edgeJump[edgeCount] = jump;
    edgeTarget[edgeCount] = target;
    edgeCount++;
    return edge;
  }

  /**
   * Gives each probe placed after the method body the handlers of the jump it belongs to: see
   * {@code MethodProbeInserter.coverEdgeProbes}.
   */
  private void coverEdgeProbes() {
    if (edgeCount == 0 || entryCount == 0) {
      return;
    }
    int searched = entryCount;
    int[] covering = new int[searched];
    int[] copyEdge = new int[8];
    int[] copyEntry = new int[8];
    int copies = 0;
    for (int e = 0; e < edgeCount; e++) {
      int depth = depthAt(edgeJump[e]);
      int size = handlersCovering(e, true, searched, covering);
      if (!throwAllowedUnder(covering, size, depth)) {
        size = handlersCovering(e, false, searched, covering);
        if (!framesSuit(covering, size, frameLocals[edgeTarget[e]])
            || !throwAllowedUnder(covering, size, depth)) {
          throw new ProbePlacementException(code.name, code.descriptor);
        }
      }
      for (int k = 0; k < size; k++) {
        if (copies == copyEdge.length) {
          copyEdge = Arrays.copyOf(copyEdge, copies * 2);
          copyEntry = Arrays.copyOf(copyEntry, copies * 2);
        }
        copyEdge[copies] = e;
        copyEntry[copies] = covering[k];
        copies++;
      }
    }
    for (int k = 0; k < copies; k++) {
      int e = copyEdge[k];
      int entry = copyEntry[k];
      addEntry(-1 - edgeStart[e], -1 - edgeEnd[e], entryHandler[entry], entryType[entry], -1);
    }
  }

  /**
   * Finds the first {@code searched} exception table entries that cover the jump of an edge, and
   * also its target if asked.
   *
   * @return how many were put in {@code covering}
   */
  private int handlersCovering(int edge, boolean target, int searched, int[] covering) {
    int jump = 8 * edgeJump[edge] + 3;
    int t = edgeTarget[edge];
    // where the code that runs first at the target lies
    int targetPosition = before[t] != null ? 8 * t + 1 : 8 * t + 3;
    int size = 0;
    for (int entry = 0; entry < searched; entry++) {
      int start = entryStart[entry];
      int end = entryEnd[entry];
      if (start < jump
          && jump < end
          && (!target || (start < targetPosition && targetPosition < end))) {
        covering[size++] = entry;
      }
    }
    return size;
  }

  /**
   * Whether code with these locals passes the verifier under the first {@code size} of these
   * entries, up to the first that catches everything.
   */
  private boolean framesSuit(int[] entries, int size, int[] locals) {
    for (int k = 0; k < size; k++) {
      int entry = entries[k];
      int[] expected = frameLocalsAt(entryHandler[entry]);
      if (expected == null) {
        throw new IllegalStateException("No stack map frame at a handler in " + code.name);
      }
      if (!localsSuit(expected, locals)) {
        return false;
      }
      if (entryType[entry] == 0) {
        return true;
      }
    }
    return true;
  }

  /** Whether the locals {@code actual} have the types that {@code expected} requires. */
  private boolean localsSuit(int[] expected, int[] actual) {
    int[] required = slotTypes(expected);
    int[] present = slotTypes(actual);
    for (int slot = 0; slot < required.length; slot++) {
      int type = required[slot];
      int found = slot < present.length ? present[slot] : TOP;
      if (type != TOP
          && !sameType(type, found)
          && !(found == NULL && StackMapFrames.tag(type) == OBJECT_TAG)) {
        return false;
      }
    }
    return true;
  }

  /** Types by local variable slot: a long or a double takes its slot and the next. */
  private static int[] slotTypes(int[] types) {
    int[] slots = new int[StackMapFrames.slots(types)];
    int slot = 0;
    for (int type : types) {
      slots[slot++] = type;
      if (StackMapFrames.isWide(type)) {
        slots[slot++] = TOP;
      }
    }
    return slots;
  }

  private boolean sameType(int a, int b) {
    if (a == b) {
      return true;
    }
    return StackMapFrames.tag(a) == OBJECT_TAG
        && StackMapFrames.tag(b) == OBJECT_TAG
        && patch
            .className(StackMapFrames.payload(a))
            .equals(patch.className(StackMapFrames.payload(b)));
  }

  /** Probe at the end of a block that falls through into a block it does not dominate. */
  private void insertFallThroughProbe(int block, int probe) {
    int next = blockFirst[block + 1];
    if (!jumpTarget[next]) {
      throw new IllegalStateException("No jump target before a merge block in " + code.name);
    }
    InsertedCode inserted = after(blockLast[block]);
    readProbe(inserted, probe, IFNE, next);
    callRecorder(inserted, probe, false);
  }

  /**
   * The slow path of a probe before a return is a copy of the return after the method body. Its
   * frame holds only the return value, so where the stack holds more the probe calls the recorder
   * unconditionally instead.
   */
  private void insertReturnProbe(int returnIndex, int probe) {
    int opcode = code.opcode[returnIndex];
    if (stackSizes[returnIndex] != returnSize()) {
      callRecorder(before(returnIndex), probe, true);
      return;
    }
    int slowPath = newLabel(NONE, returnStack(opcode));
    readProbe(before(returnIndex), probe, IFEQ, slowPath);
    tail.label(slowPath);
    callRecorder(tail, probe, false);
    tail.opcode(opcode);
  }

  /** See {@code MethodProbeInserter.insertProgressStores}. */
  private void insertProgressStores(int block, boolean[] uninitializedThis, int recording) {
    if (recording == -1) {
      return;
    }
    if (recording >= 0) {
      recordProgress(before(recording));
    }
    LineList lines = new LineList();
    int segmentFirst = -1;
    boolean segmentThrows = false;
    for (int i = blockFirst[block]; i <= blockLast[block]; i++) {
      if (lineOf[i] >= 0 && !lines.contains(lineOf[i])) {
        if (segmentThrows) {
          storeProgress(block, Math.max(segmentFirst, recording), lines);
        }
        lines.add(lineOf[i]);
        segmentFirst = -1;
        segmentThrows = false;
      }
      if (uninitializedThis != null && uninitializedThis[i]) {
        continue; // no handler covers code that runs before this is initialized
      }
      if (segmentFirst < 0) {
        segmentFirst = i;
      }
      segmentThrows |= mayThrow(i);
    }
    if (segmentThrows) {
      storeProgress(block, Math.max(segmentFirst, recording), lines);
    }
  }

  private void storeProgress(int block, int first, LineList lines) {
    int probe =
        lines.size() == blockLines[block].length
            ? blockProbe(block)
            : probes.newProbe(lines.toArray(), parentNode(block));
    InsertedCode inserted = before(first);
    pushWithoutLdc(inserted, probe);
    inserted.variable(ISTORE, progressLocal);
  }

  /**
   * For each block, where it records the probe stored before an exception as the first block of
   * exception handlers: see {@code MethodProbeInserter.insertProgressStores}.
   */
  private int[] handlerRecordings() {
    int[] recordings = new int[blockCount];
    Arrays.fill(recordings, -2);
    for (int h = 0; h < code.handlerCount; h++) {
      int entry = code.handlerEntry[h];
      if (recordings[blockOf[entry]] == -2) {
        recordings[blockOf[entry]] = recordingPosition(entry);
      }
    }
    return recordings;
  }

  /** See {@code MethodProbeInserter.recordingPosition}. */
  private int recordingPosition(int entry) {
    int block = blockOf[entry];
    for (int i = entry; i <= blockLast[block]; i++) {
      int depth = depthAt(i);
      if (throwAllowedAt(i, depth)) {
        return i;
      }
      if (depth < 0 || (canTrap(i, depth) && !reentersBlock(i, block, depth))) {
        return -1;
      }
    }
    return -1;
  }

  /** See {@code MethodProbeInserter.reentersBlock}. */
  private boolean reentersBlock(int i, int block, int depth) {
    if (depth != 0) {
      return false;
    }
    for (int h = 0; h < code.handlerCount; h++) {
      if (covers(h, i)) {
        if (blockOf[code.handlerEntry[h]] != block || handlerDepth(h) != 0) {
          return false;
        }
        if (catchesAll(h)) {
          return true;
        }
      }
    }
    return false;
  }

  /**
   * Adds the handler that records the stored probe when an exception leaves the method, last in the
   * exception table so that the method's own handlers keep precedence, and rethrows the exception.
   * It covers the method body except where {@code this} is not initialized yet or a monitor is
   * held.
   */
  private void addThrownHandler(boolean[] uninitializedThis) {
    int[] rangeFirst = new int[4];
    int[] rangeLast = new int[4];
    int ranges = 0;
    int first = -1;
    int last = -1;
    for (int i = 0; i <= count; i++) {
      boolean covered =
          i < count
              && (uninitializedThis == null || !uninitializedThis[i])
              && (monitorDepths == null || monitorDepths[i] == 0);
      if (covered) {
        if (first < 0) {
          first = i;
        }
        last = i;
      } else if (first >= 0) {
        if (ranges == rangeFirst.length) {
          rangeFirst = Arrays.copyOf(rangeFirst, ranges * 2);
          rangeLast = Arrays.copyOf(rangeLast, ranges * 2);
        }
        rangeFirst[ranges] = first;
        rangeLast[ranges] = last;
        ranges++;
        first = -1;
      }
    }
    if (ranges == 0) {
      return;
    }
    int[] locals = new int[progressLocal + 1]; // all TOP
    if (probesLocal >= 0) {
      locals[probesLocal] = patch.probesType();
    }
    locals[progressLocal] = INTEGER;
    int handler = newLabel(locals, new int[] {patch.throwableType()});
    thrown = new InsertedCode();
    thrown.label(handler);
    recordProgress(thrown);
    thrown.opcode(ATHROW);
    for (int r = 0; r < ranges; r++) {
      addEntry(8 * rangeFirst[r] + 2, 8 * rangeLast[r] + 4, handler, 0, -1);
    }
  }

  private void recordProgress(InsertedCode inserted) {
    push(inserted, classId);
    inserted.variable(ILOAD, progressLocal);
    inserted.member(
        INVOKESTATIC, initializer ? patch.recordInitThrownMethod() : patch.recordThrownMethod());
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
   * Marks the instructions of a constructor that run while {@code this} is uninitialized. Frames
   * give the state wherever control flow merges, and the call that initializes {@code this} changes
   * it.
   */
  private boolean[] findUninitializedThis() {
    boolean[] uninitialized = new boolean[count];
    boolean thisUninitialized = true;
    int pendingNew = 0;
    for (int i = 0; i < count; i++) {
      if (frameLocals[i] != null) {
        thisUninitialized = false;
        for (int type : frameLocals[i]) {
          thisUninitialized |= type == UNINITIALIZED_THIS;
        }
        pendingNew = 0;
        for (int type : frameStack[i]) {
          if (StackMapFrames.tag(type) == UNINITIALIZED_TAG) {
            pendingNew++; // an object created by NEW and not yet initialized
          }
        }
      }
      uninitialized[i] = thisUninitialized;
      int opcode = code.opcode[i];
      if (opcode == NEW) {
        pendingNew++;
      } else if (opcode == INVOKESPECIAL && code.callsConstructor(i)) {
        if (pendingNew > 0) {
          pendingNew--;
        } else {
          thisUninitialized = false;
        }
      }
    }
    return uninitialized;
  }

  private void readProbe(InsertedCode inserted, int probe, int jumpOpcode, int label) {
    if (probesLocal >= 0) {
      inserted.variable(ALOAD, probesLocal);
    } else if (initializer) {
      inserted.member(GETSTATIC, patch.probesField());
      push(inserted, classId);
      inserted.opcode(AALOAD);
    } else {
      takeProbes(inserted);
    }
    push(inserted, probe);
    inserted.opcode(BALOAD);
    inserted.jump(jumpOpcode, label);
  }

  /** Pushes the probe array of the running code's owner. */
  private void takeProbes(InsertedCode inserted) {
    push(inserted, classId);
    inserted.member(INVOKESTATIC, patch.probesMethod());
  }

  /**
   * @param unconditional whether the call is made on every execution, so the recorder must check
   *     the slot itself
   */
  private void callRecorder(InsertedCode inserted, int probe, boolean unconditional) {
    if (initializer) {
      push(inserted, classId);
      push(inserted, probe);
      inserted.member(INVOKESTATIC, patch.recordInitMethod());
    } else if (unconditional) {
      inserted.variable(ALOAD, probesLocal);
      push(inserted, classId);
      push(inserted, probe);
      inserted.member(INVOKESTATIC, patch.hitMethod());
    } else {
      push(inserted, classId);
      push(inserted, probe);
      inserted.member(INVOKESTATIC, patch.recordMethod());
    }
  }

  /**
   * Pushes an int without LDC, which HotSpot takes to possibly throw. Probe ids stay far below
   * 2^30, so both halves fit a SIPUSH.
   */
  private void pushWithoutLdc(InsertedCode inserted, int value) {
    if (value <= Short.MAX_VALUE) {
      push(inserted, value);
    } else {
      push(inserted, value >>> 15);
      push(inserted, 15);
      inserted.opcode(ISHL);
      push(inserted, value & Short.MAX_VALUE);
      inserted.opcode(IOR);
    }
  }

  private void push(InsertedCode inserted, int value) {
    inserted.push(value, value > Short.MAX_VALUE ? patch.integerConstant(value) : 0);
  }

  /**
   * Adds a one-slot local variable after the method's own. It is set before any original code runs
   * and keeps its type, so every stack map frame of the method gets that type.
   *
   * @return the local's index
   */
  private int addLocal(int type) {
    int local = maxLocals++;
    for (int i = 0; i < count; i++) {
      int[] locals = frameLocals[i];
      if (locals != null) {
        // frames may share their locals, so each gets a copy
        int padding = Math.max(local - StackMapFrames.slots(locals), 0);
        int[] extended = Arrays.copyOf(locals, locals.length + padding + 1);
        extended[extended.length - 1] = type;
        frameLocals[i] = extended;
      }
    }
    return local;
  }

  /** The locals of the frame at the method's entry: {@code this}, then the arguments. */
  private int[] entryLocals() {
    if (entryLocals == null) {
      String descriptor = code.descriptor;
      int[] locals = new int[descriptor.length() + 1];
      int size = 0;
      if ((code.access & ACC_STATIC) == 0) {
        locals[size++] = "<init>".equals(code.name) ? UNINITIALIZED_THIS : patch.thisType();
      }
      int at = 1;
      while (descriptor.charAt(at) != ')') {
        int end = ClassFilePatcher.typeEnd(descriptor, at);
        locals[size++] = patch.frameType(descriptor, at, end);
        at = end;
      }
      entryLocals = Arrays.copyOf(locals, size);
    }
    return entryLocals;
  }

  /** The operand stack slots of the method's return value. */
  private int returnSize() {
    String descriptor = code.descriptor;
    char type = descriptor.charAt(descriptor.indexOf(')') + 1);
    return type == 'V' ? 0 : type == 'J' || type == 'D' ? 2 : 1;
  }

  private int[] returnStack(int opcode) {
    switch (opcode) {
      case IRETURN:
        return new int[] {INTEGER};
      case LRETURN:
        return new int[] {LONG};
      case FRETURN:
        return new int[] {FLOAT};
      case DRETURN:
        return new int[] {DOUBLE};
      case ARETURN:
        String descriptor = code.descriptor;
        int at = descriptor.indexOf(')') + 1;
        return new int[] {
          patch.frameType(descriptor, at, ClassFilePatcher.typeEnd(descriptor, at))
        };
      default:
        return NONE;
    }
  }

  private void computeStackSizes() {
    stackSizes = new int[count];
    int size = 0;
    for (int i = 0; i < count; i++) {
      if (frameLocals[i] != null) {
        size = StackMapFrames.slots(frameStack[i]);
      }
      stackSizes[i] = size;
      if (size < 0) {
        continue;
      }
      int opcode = code.opcode[i];
      if (opcode == GOTO
          || opcode == TABLESWITCH
          || opcode == LOOKUPSWITCH
          || opcode == ATHROW
          || isReturn(opcode)) {
        size = -1;
      } else {
        size += code.stackDelta(i);
      }
    }
  }

  private void initLabels() {
    labelCount = count + 1;
    before = new InsertedCode[count];
    after = new InsertedCode[count];
    jumpLabel = code.target.clone();
    switchLabels = new int[count][];
    for (int i = 0; i < count; i++) {
      if (code.switchTargets[i] != null) {
        switchLabels[i] = code.switchTargets[i].clone();
      }
    }
    for (int h = 0; h < code.handlerCount; h++) {
      addEntry(
          8 * code.handlerStart[h],
          8 * code.handlerEnd[h],
          code.handlerEntry[h],
          code.handlerType[h],
          h);
    }
  }

  private int newLabel(int[] locals, int[] stack) {
    int label = labelCount++;
    int k = label - count - 1;
    if (k == insertedFrameLocals.length) {
      insertedFrameLocals = Arrays.copyOf(insertedFrameLocals, k * 2);
      insertedFrameStack = Arrays.copyOf(insertedFrameStack, k * 2);
    }
    insertedFrameLocals[k] = locals;
    insertedFrameStack[k] = stack;
    return label;
  }

  private int[] frameLocalsAt(int label) {
    if (label < count) {
      return frameLocals[label];
    }
    return label == count ? null : insertedFrameLocals[label - count - 1];
  }

  private int[] frameStackAt(int label) {
    if (label < count) {
      return frameStack[label];
    }
    return label == count ? null : insertedFrameStack[label - count - 1];
  }

  private void addEntry(int start, int end, int handler, int type, int original) {
    if (entryCount == entryStart.length) {
      int length = entryCount * 2;
      entryStart = Arrays.copyOf(entryStart, length);
      entryEnd = Arrays.copyOf(entryEnd, length);
      entryHandler = Arrays.copyOf(entryHandler, length);
      entryType = Arrays.copyOf(entryType, length);
      entryOriginal = Arrays.copyOf(entryOriginal, length);
    }
    entryStart[entryCount] = start;
    entryEnd[entryCount] = end;
    entryHandler[entryCount] = handler;
    entryType[entryCount] = type;
    entryOriginal[entryCount] = original;
    entryCount++;
  }

  private InsertedCode before(int i) {
    if (before[i] == null) {
      before[i] = new InsertedCode();
    }
    return before[i];
  }

  private InsertedCode after(int i) {
    if (after[i] == null) {
      after[i] = new InsertedCode();
    }
    return after[i];
  }

  /**
   * Lays out the patched code.
   *
   * @throws UnsupportedClassException if the code is too long for every jump to keep a 2-byte
   *     offset
   */
  private void layout() {
    labelOffset = new int[labelCount];
    instructionOffset = new int[count];
    instructionEnd = new int[count];
    int at = prologue != null ? prologue.layout(0, labelOffset) : 0;
    for (int i = 0; i < count; i++) {
      labelOffset[i] = at;
      if (before[i] != null) {
        at = before[i].layout(at, labelOffset);
      }
      instructionOffset[i] = at;
      at += instructionLength(i, at);
      instructionEnd[i] = at;
      if (after[i] != null) {
        at = after[i].layout(at, labelOffset);
      }
    }
    labelOffset[count] = at;
    at = tail.layout(at, labelOffset);
    thrownOffset = at;
    if (thrown != null) {
      at = thrown.layout(at, labelOffset);
    }
    if (at > Short.MAX_VALUE) {
      throw new UnsupportedClassException(CODE_TOO_LONG);
    }
    codeLength = at;
  }

  private int instructionLength(int i, int at) {
    int length = code.offset[i + 1] - code.offset[i];
    int opcode = code.rawOpcode(i);
    if (opcode == TABLESWITCH || opcode == LOOKUPSWITCH) {
      return length
          - InstructionSet.switchPadding(code.offset[i])
          + InstructionSet.switchPadding(at);
    }
    return length;
  }

  /** Writes the patched method's Code attribute. */
  void write(ByteArrayBuilder out, int attributeName) {
    layout();
    // frames are collected first: a method without a StackMapTable may need one, and its name
    int frames = 0;
    int[] frameOffsets = new int[16];
    int[] frameLabels = new int[16];
    for (int label = 0; label < labelCount; label++) {
      if (frameLocalsAt(label) == null) {
        continue;
      }
      if (frames == frameOffsets.length) {
        frameOffsets = Arrays.copyOf(frameOffsets, frames * 2);
        frameLabels = Arrays.copyOf(frameLabels, frames * 2);
      }
      int offset = labelOffset[label];
      // insertion sort: only a frame inserted at the method entry comes out of order
      int k = frames++;
      while (k > 0 && frameOffsets[k - 1] > offset) {
        frameOffsets[k] = frameOffsets[k - 1];
        frameLabels[k] = frameLabels[k - 1];
        k--;
      }
      frameOffsets[k] = offset;
      frameLabels[k] = label;
    }

    out.putShort(attributeName);
    int lengthAt = out.length;
    out.putInt(0);
    out.putShort(Math.max(code.maxStack + 3, 4)).putShort(maxLocals).putInt(codeLength);
    int codeStart = out.length;
    if (prologue != null) {
      prologue.write(out, 0, labelOffset);
    }
    for (int i = 0; i < count; i++) {
      if (before[i] != null) {
        before[i].write(out, labelOffset[i], labelOffset);
      }
      writeInstruction(out, i);
      if (after[i] != null) {
        after[i].write(out, instructionEnd[i], labelOffset);
      }
    }
    tail.write(out, labelOffset[count], labelOffset);
    if (thrown != null) {
      thrown.write(out, thrownOffset, labelOffset);
    }
    if (out.length - codeStart != codeLength) {
      throw new IllegalStateException("Code laid out and written differ in " + code.name);
    }

    out.putShort(entryCount);
    for (int e = 0; e < entryCount; e++) {
      out.putShort(offsetOf(entryStart[e]))
          .putShort(offsetOf(entryEnd[e]))
          .putShort(labelOffset[entryHandler[e]])
          .putShort(entryType[e]);
    }

    out.putShort(code.tableCount + (frames > 0 ? 1 : 0));
    for (int t = 0; t < code.tableCount; t++) {
      writeTable(out, t);
    }
    if (frames > 0) {
      writeStackMapTable(out, frames, frameOffsets, frameLabels);
    }
    out.setInt(lengthAt, out.length - lengthAt - 4);
  }

  private int offsetOf(int position) {
    if (position < 0) {
      return labelOffset[-1 - position];
    }
    int i = position >> 3;
    switch (position & 7) {
      case 0:
        return labelOffset[i];
      case 2:
        return instructionOffset[i];
      default:
        return instructionEnd[i];
    }
  }

  private void writeInstruction(ByteArrayBuilder out, int i) {
    byte[] bytes = code.classFile.bytes;
    int start = code.codeStart + code.offset[i];
    int opcode = bytes[start] & 0xFF;
    int at = instructionOffset[i];
    if (InstructionSet.isShortJump(opcode)) {
      out.putByte(opcode).putShort(labelOffset[jumpLabel[i]] - at);
    } else if (opcode == InstructionSet.GOTO_W || opcode == InstructionSet.JSR_W) {
      out.putByte(opcode).putInt(labelOffset[jumpLabel[i]] - at);
    } else if (opcode == TABLESWITCH || opcode == LOOKUPSWITCH) {
      out.putByte(opcode);
      for (int k = InstructionSet.switchPadding(at); k > 0; k--) {
        out.putByte(0);
      }
      int base = start + 1 + InstructionSet.switchPadding(code.offset[i]);
      int[] labels = switchLabels[i];
      out.putInt(labelOffset[labels[0]] - at);
      if (opcode == TABLESWITCH) {
        out.putBytes(bytes, base + 4, 8); // low and high
        for (int k = 1; k < labels.length; k++) {
          out.putInt(labelOffset[labels[k]] - at);
        }
      } else {
        out.putBytes(bytes, base + 4, 4); // pair count
        for (int k = 1; k < labels.length; k++) {
          out.putBytes(bytes, base + 8 * k, 4); // key
          out.putInt(labelOffset[labels[k]] - at);
        }
      }
    } else {
      out.putBytes(bytes, start, code.offset[i + 1] - code.offset[i]);
    }
  }

  private void writeTable(ByteArrayBuilder out, int t) {
    ClassFile classFile = code.classFile;
    int content = code.tableContent[t];
    int entries = classFile.readUnsignedShort(content);
    if (code.tableKind[t] == MethodCode.LINE_NUMBERS) {
      out.putShort(code.tableName[t]).putInt(2 + 4 * entries).putShort(entries);
      for (int k = 0; k < entries; k++) {
        int entry = content + 2 + 4 * k;
        int pc = classFile.readUnsignedShort(entry);
        out.putShort(labelOffset[code.indexAt[pc]])
            .putShort(classFile.readUnsignedShort(entry + 2));
      }
    } else {
      out.putShort(code.tableName[t]).putInt(2 + 10 * entries).putShort(entries);
      for (int k = 0; k < entries; k++) {
        int entry = content + 2 + 10 * k;
        int start = classFile.readUnsignedShort(entry);
        int end = start + classFile.readUnsignedShort(entry + 2);
        int newStart = labelOffset[code.indexAt[start]];
        int newEnd = labelOffset[code.indexAt[end]];
        out.putShort(newStart).putShort(newEnd - newStart).putBytes(classFile.bytes, entry + 4, 6);
      }
    }
  }

  private void writeStackMapTable(
      ByteArrayBuilder out, int frames, int[] frameOffsets, int[] frameLabels) {
    int name = code.stackMapTable >= 0 ? code.stackMapTableName : patch.stackMapTableName();
    out.putShort(name);
    int lengthAt = out.length;
    out.putInt(0);
    out.putShort(frames);
    int previous = -1;
    for (int k = 0; k < frames; k++) {
      int offset = frameOffsets[k];
      if (offset <= previous) {
        throw new UnsupportedClassException(SAME_FRAME_OFFSET);
      }
      int[] locals = frameLocalsAt(frameLabels[k]);
      int[] stack = frameStackAt(frameLabels[k]);
      out.putByte(255).putShort(offset - previous - 1).putShort(locals.length);
      for (int type : locals) {
        StackMapFrames.writeType(out, type, code, instructionOffset);
      }
      out.putShort(stack.length);
      for (int type : stack) {
        StackMapFrames.writeType(out, type, code, instructionOffset);
      }
      previous = offset;
    }
    out.setInt(lengthAt, out.length - lengthAt - 4);
  }

  private static boolean isReturn(int opcode) {
    return opcode >= IRETURN && opcode <= RETURN;
  }
}
