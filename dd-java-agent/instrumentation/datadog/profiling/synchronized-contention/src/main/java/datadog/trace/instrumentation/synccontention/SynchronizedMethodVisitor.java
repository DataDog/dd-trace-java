package datadog.trace.instrumentation.synccontention;

import datadog.trace.agent.tooling.bytebuddy.reqctx.LocalVariablesSorter;
import net.bytebuddy.jar.asm.Label;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;
import net.bytebuddy.jar.asm.Type;
import net.bytebuddy.utility.OpenedClassReader;

/**
 * Rewrites {@code MONITORENTER} opcodes and (when {@code wasSynchronized}) the implicit lock
 * acquired by {@code ACC_SYNCHRONIZED} into explicit capture / finish calls around the entry-queue
 * wait. {@code MONITOREXIT} is intentionally <i>not</i> instrumented because releasing a held lock
 * is not a blocking event in JVM semantics.
 *
 * <p>For each block-level {@code MONITORENTER} the visitor emits:
 *
 * <pre>
 *   ... obj_ref
 *   DUP                                         ; obj_ref obj_ref
 *   INVOKESTATIC TaskBlockHelper.captureForMonitor(Object)State
 *   ASTORE  &lt;state&gt;                       ; obj_ref
 *   MONITORENTER                                ; (entry-queue wait ends here)
 *   ALOAD   &lt;state&gt;
 *   INVOKESTATIC TaskBlockHelper.finish(State)V
 * </pre>
 *
 * <p>For {@code ACC_SYNCHRONIZED} methods the {@code RewritingClassVisitor} has already stripped
 * the flag. This visitor materializes the lock by emitting at {@link #visitCode()}:
 *
 * <pre>
 *   ; instance method: load receiver. static method: LDC owner class literal.
 *   ALOAD 0  /  LDC Type.getObjectType("Lowner;")
 *   DUP                                         ; lock lock
 *   ASTORE  &lt;lock&gt;                        ; lock          ; cache for unlocks
 *   DUP                                         ; lock lock
 *   INVOKESTATIC captureForMonitor              ; lock state
 *   ASTORE  &lt;state&gt;                       ; lock
 *   MONITORENTER                                ; (acquired)
 *   ; tryStart: — handler covers finish() so an instrumentation error cannot leak the lock
 *   ALOAD   &lt;state&gt;
 *   INVOKESTATIC finish
 * </pre>
 *
 * Each return opcode is preceded with {@code ALOAD &lt;lock&gt;; MONITOREXIT}. Before {@link
 * #visitMaxs(int, int)} the visitor emits {@code tryEnd:} and the synthetic handler {@code
 * handler:} block ({@code ALOAD &lt;lock&gt;; MONITOREXIT; ATHROW}) and registers a single
 * try-catch block covering the original body.
 */
final class SynchronizedMethodVisitor extends LocalVariablesSorter {

  static final String OBJECT_INTERNAL = "java/lang/Object";
  static final String OBJECT_DESC = "Ljava/lang/Object;";
  static final String STATE_INTERNAL =
      "datadog/trace/bootstrap/instrumentation/java/concurrent/TaskBlockHelper$State";
  static final String STATE_DESC =
      "Ldatadog/trace/bootstrap/instrumentation/java/concurrent/TaskBlockHelper$State;";
  static final String TASK_BLOCK_HELPER =
      "datadog/trace/bootstrap/instrumentation/java/concurrent/TaskBlockHelper";
  static final String CAPTURE_FOR_MONITOR_DESC = "(" + OBJECT_DESC + ")" + STATE_DESC;
  static final String FINISH_DESC = "(" + STATE_DESC + ")V";

  private static final Type OBJECT_TYPE = Type.getObjectType(OBJECT_INTERNAL);
  private static final Type STATE_TYPE = Type.getObjectType(STATE_INTERNAL);

  private final boolean wasSynchronized;
  private final boolean isStatic;
  private final String declaringInternalName;

  /** Slot for the cached lock target. Only valid when {@link #wasSynchronized}. */
  private int lockLocal = -1;

  // try-catch labels for the ACC_SYNCHRONIZED-derived implicit handler.
  private final Label tryStart = new Label();
  private final Label tryEnd = new Label();
  private final Label handler = new Label();

  SynchronizedMethodVisitor(
      final int access,
      final String descriptor,
      final MethodVisitor delegate,
      final boolean wasSynchronized,
      final boolean isStatic,
      final String declaringInternalName) {
    super(OpenedClassReader.ASM_API, access, descriptor, delegate);
    this.wasSynchronized = wasSynchronized;
    this.isStatic = isStatic;
    this.declaringInternalName = declaringInternalName;
  }

  @Override
  public void visitCode() {
    super.visitCode();
    if (!wasSynchronized) {
      return;
    }
    // Allocate locals AFTER super.visitCode so LocalVariablesSorter has finished initializing.
    lockLocal = newLocal(OBJECT_TYPE);
    final int stateLocal = newLocal(STATE_TYPE);

    // Try-catch block must be declared before the labels are visited.
    super.visitTryCatchBlock(tryStart, tryEnd, handler, null);

    // Load lock target.
    if (isStatic) {
      super.visitLdcInsn(Type.getObjectType(declaringInternalName));
    } else {
      super.visitVarInsn(Opcodes.ALOAD, 0);
    }
    // Stack: [lock]
    super.visitInsn(Opcodes.DUP);
    // Stack: [lock, lock]
    visitNewLocalVarInsn(Opcodes.ASTORE, lockLocal);
    // Stack: [lock]
    super.visitInsn(Opcodes.DUP);
    // Stack: [lock, lock]
    super.visitMethodInsn(
        Opcodes.INVOKESTATIC,
        TASK_BLOCK_HELPER,
        "captureForMonitor",
        CAPTURE_FOR_MONITOR_DESC,
        false);
    // Stack: [lock, state]
    visitNewLocalVarInsn(Opcodes.ASTORE, stateLocal);
    // Stack: [lock]
    super.visitInsn(Opcodes.MONITORENTER);
    // Stack: []  ; lock now held — protected region starts here so the handler covers finish() too.
    super.visitLabel(tryStart);
    visitNewLocalVarInsn(Opcodes.ALOAD, stateLocal);
    super.visitMethodInsn(Opcodes.INVOKESTATIC, TASK_BLOCK_HELPER, "finish", FINISH_DESC, false);
  }

  @Override
  public void visitInsn(final int opcode) {
    if (opcode == Opcodes.MONITORENTER) {
      // Block-level synchronized(obj) { ... }. Wrap the original MONITORENTER.
      // Incoming stack: [..., obj]
      final int stateLocal = newLocal(STATE_TYPE);
      super.visitInsn(Opcodes.DUP); // [obj, obj]
      super.visitMethodInsn(
          Opcodes.INVOKESTATIC,
          TASK_BLOCK_HELPER,
          "captureForMonitor",
          CAPTURE_FOR_MONITOR_DESC,
          false); // [obj, state]
      visitNewLocalVarInsn(Opcodes.ASTORE, stateLocal); // [obj]
      super.visitInsn(Opcodes.MONITORENTER); // []
      visitNewLocalVarInsn(Opcodes.ALOAD, stateLocal); // [state]
      super.visitMethodInsn(Opcodes.INVOKESTATIC, TASK_BLOCK_HELPER, "finish", FINISH_DESC, false);
      return;
    }
    if (wasSynchronized && isReturnOpcode(opcode)) {
      // Release the implicit lock immediately before the user's return.
      visitNewLocalVarInsn(Opcodes.ALOAD, lockLocal);
      super.visitInsn(Opcodes.MONITOREXIT);
    }
    super.visitInsn(opcode);
  }

  @Override
  public void visitMaxs(final int maxStack, final int maxLocals) {
    if (wasSynchronized) {
      // Close the protected region and emit the synthetic handler.
      super.visitLabel(tryEnd);
      super.visitLabel(handler);
      visitNewLocalVarInsn(Opcodes.ALOAD, lockLocal);
      super.visitInsn(Opcodes.MONITOREXIT);
      super.visitInsn(Opcodes.ATHROW);
    }
    // COMPUTE_FRAMES (requested by SynchronizedRewritingVisitor.mergeWriter) lets ASM recompute
    // stack-map frames and max sizes from scratch, so the original (0, 0) hint is sufficient.
    super.visitMaxs(maxStack, maxLocals);
  }

  private static boolean isReturnOpcode(final int opcode) {
    switch (opcode) {
      case Opcodes.IRETURN:
      case Opcodes.LRETURN:
      case Opcodes.FRETURN:
      case Opcodes.DRETURN:
      case Opcodes.ARETURN:
      case Opcodes.RETURN:
        return true;
      default:
        return false;
    }
  }

  private void visitNewLocalVarInsn(final int opcode, final int local) {
    mv.visitVarInsn(opcode, local);
  }
}
