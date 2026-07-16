// Copyright 2026 Datadog, Inc.
package datadog.trace.instrumentation.threadsleep;

import datadog.trace.agent.tooling.bytebuddy.reqctx.LocalVariablesSorter;
import net.bytebuddy.jar.asm.Label;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;
import net.bytebuddy.jar.asm.Type;
import net.bytebuddy.pool.TypePool;
import net.bytebuddy.utility.OpenedClassReader;

/**
 * Wraps each {@code INVOKESTATIC java/lang/Thread.sleep(...)V} and {@code INVOKEVIRTUAL
 * java/util/concurrent/TimeUnit.sleep(J)V} call site in a TaskBlock capture/finish pair with a
 * try-finally so the {@code finish} runs even when the sleep throws {@code InterruptedException}.
 *
 * <p>For {@code Thread.sleep(J)V} the emitted shape at each call site is:
 *
 * <pre>
 *   ; incoming stack: [..., millis]
 *   LSTORE  &lt;tmpLong&gt;                            ; cache the arg
 *   INVOKESTATIC TaskBlockHelper.captureForSleep()State
 *   ASTORE  &lt;state&gt;
 *   tryStart:
 *   LLOAD   &lt;tmpLong&gt;
 *   INVOKESTATIC Thread.sleep(J)V                     ; the original call
 *   ALOAD   &lt;state&gt;
 *   INVOKESTATIC TaskBlockHelper.finish(State)V
 *   GOTO end
 *   tryEnd / handler:
 *   ASTORE  &lt;throwable&gt;
 *   ALOAD   &lt;state&gt;
 *   INVOKESTATIC TaskBlockHelper.finish(State)V
 *   ALOAD   &lt;throwable&gt;
 *   ATHROW
 *   end:
 * </pre>
 *
 * <p>For {@code Thread.sleep(JI)V} the incoming stack is {@code [..., millis, nanos]} (int on top);
 * ISTORE pops first, LSTORE second, and the load sequence mirrors that.
 *
 * <p>For {@code Thread.sleep(java.time.Duration)V} (JDK 19+) the incoming stack is {@code [...,
 * duration]} (one object reference on top); ASTORE pops the reference and ALOAD re-pushes it before
 * the original call. The {@code Duration} overload <em>cannot</em> be relied upon to delegate to
 * {@code sleep(J,I)V} at the bytecode level — the internal delegation call lives inside {@code
 * java.lang.Thread} which is excluded from instrumentation. Each {@code Thread.sleep(Duration)}
 * call site in user code is therefore wrapped directly.
 */
final class ThreadSleepCallSiteMethodVisitor extends LocalVariablesSorter {

  static final String THREAD_INTERNAL = "java/lang/Thread";
  static final String TIME_UNIT_INTERNAL = "java/util/concurrent/TimeUnit";
  static final String STATE_INTERNAL =
      "datadog/trace/bootstrap/instrumentation/java/concurrent/TaskBlockHelper$State";
  static final String STATE_DESC = "L" + STATE_INTERNAL + ";";
  static final String TASK_BLOCK_HELPER =
      "datadog/trace/bootstrap/instrumentation/java/concurrent/TaskBlockHelper";
  static final String CAPTURE_FOR_SLEEP_DESC = "()" + STATE_DESC;
  static final String FINISH_DESC = "(" + STATE_DESC + ")V";

  static final String SLEEP_J_DESC = "(J)V";
  static final String SLEEP_JI_DESC = "(JI)V";
  static final String SLEEP_DURATION_DESC = "(Ljava/time/Duration;)V";

  private static final Type LONG_TYPE = Type.LONG_TYPE;
  private static final Type INT_TYPE = Type.INT_TYPE;
  private static final Type DURATION_TYPE = Type.getObjectType("java/time/Duration");
  private static final Type TIME_UNIT_TYPE = Type.getObjectType(TIME_UNIT_INTERNAL);
  private static final Type STATE_TYPE = Type.getObjectType(STATE_INTERNAL);
  private static final Type THROWABLE_TYPE = Type.getObjectType("java/lang/Throwable");
  private final TypePool typePool;

  ThreadSleepCallSiteMethodVisitor(
      final int access,
      final String descriptor,
      final MethodVisitor delegate,
      final TypePool typePool) {
    super(OpenedClassReader.ASM_API, access, descriptor, delegate);
    this.typePool = typePool;
  }

  @Override
  public void visitMethodInsn(
      final int opcode,
      final String owner,
      final String name,
      final String descriptor,
      final boolean isInterface) {
    boolean supported =
        ThreadSleepCallSiteMatcher.isSupported(opcode, owner, name, descriptor, typePool);
    if (supported && opcode == Opcodes.INVOKESTATIC) {
      if (SLEEP_DURATION_DESC.equals(descriptor)) {
        emitWrappedDurationSleepCall(owner, name, isInterface);
      } else {
        emitWrappedSleepCall(owner, name, descriptor, isInterface);
      }
      return;
    }
    if (supported) {
      emitWrappedTimeUnitSleepCall(owner, name, descriptor, isInterface);
      return;
    }
    super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
  }

  private void emitWrappedSleepCall(
      final String owner, final String name, final String descriptor, final boolean isInterface) {
    final boolean isJI = SLEEP_JI_DESC.equals(descriptor);
    final int tmpLong = newLocal(LONG_TYPE);
    final int tmpInt = isJI ? newLocal(INT_TYPE) : -1;
    final int stateLocal = newLocal(STATE_TYPE);
    final int throwableLocal = newLocal(THROWABLE_TYPE);

    final Label tryStart = new Label();
    final Label tryEnd = new Label();
    final Label handler = new Label();
    final Label end = new Label();

    super.visitTryCatchBlock(tryStart, tryEnd, handler, null);

    // Pop args into locals in reverse stack order (top first).
    if (isJI) {
      visitNewLocalVarInsn(Opcodes.ISTORE, tmpInt);
    }
    visitNewLocalVarInsn(Opcodes.LSTORE, tmpLong);

    // captureForSleep() is null whenever the integration or native entry rejects the interval.
    super.visitMethodInsn(
        Opcodes.INVOKESTATIC, TASK_BLOCK_HELPER, "captureForSleep", CAPTURE_FOR_SLEEP_DESC, false);
    visitNewLocalVarInsn(Opcodes.ASTORE, stateLocal);

    // Protected region: the original Thread.sleep call.
    super.visitLabel(tryStart);
    visitNewLocalVarInsn(Opcodes.LLOAD, tmpLong);
    if (isJI) {
      visitNewLocalVarInsn(Opcodes.ILOAD, tmpInt);
    }
    super.visitMethodInsn(Opcodes.INVOKESTATIC, owner, name, descriptor, isInterface);
    visitNewLocalVarInsn(Opcodes.ALOAD, stateLocal);
    super.visitMethodInsn(Opcodes.INVOKESTATIC, TASK_BLOCK_HELPER, "finish", FINISH_DESC, false);
    super.visitJumpInsn(Opcodes.GOTO, end);

    // Exception handler: finish first, then rethrow. finish() tolerates null State, so the no-op
    // case is harmless.
    super.visitLabel(tryEnd);
    super.visitLabel(handler);
    visitNewLocalVarInsn(Opcodes.ASTORE, throwableLocal);
    visitNewLocalVarInsn(Opcodes.ALOAD, stateLocal);
    super.visitMethodInsn(Opcodes.INVOKESTATIC, TASK_BLOCK_HELPER, "finish", FINISH_DESC, false);
    visitNewLocalVarInsn(Opcodes.ALOAD, throwableLocal);
    super.visitInsn(Opcodes.ATHROW);

    super.visitLabel(end);
  }

  /**
   * Emits the wrapped form for {@code TimeUnit.sleep(long)}. The incoming stack has {@code [...,
   * timeUnit, timeout]}; the long argument is popped first, then the receiver reference.
   */
  private void emitWrappedTimeUnitSleepCall(
      final String owner, final String name, final String descriptor, final boolean isInterface) {
    final int tmpLong = newLocal(LONG_TYPE);
    final int tmpTimeUnit = newLocal(TIME_UNIT_TYPE);
    final int stateLocal = newLocal(STATE_TYPE);
    final int throwableLocal = newLocal(THROWABLE_TYPE);

    final Label tryStart = new Label();
    final Label tryEnd = new Label();
    final Label handler = new Label();
    final Label end = new Label();

    super.visitTryCatchBlock(tryStart, tryEnd, handler, null);

    visitNewLocalVarInsn(Opcodes.LSTORE, tmpLong);
    visitNewLocalVarInsn(Opcodes.ASTORE, tmpTimeUnit);

    super.visitMethodInsn(
        Opcodes.INVOKESTATIC, TASK_BLOCK_HELPER, "captureForSleep", CAPTURE_FOR_SLEEP_DESC, false);
    visitNewLocalVarInsn(Opcodes.ASTORE, stateLocal);

    super.visitLabel(tryStart);
    visitNewLocalVarInsn(Opcodes.ALOAD, tmpTimeUnit);
    visitNewLocalVarInsn(Opcodes.LLOAD, tmpLong);
    super.visitMethodInsn(Opcodes.INVOKEVIRTUAL, owner, name, descriptor, isInterface);
    visitNewLocalVarInsn(Opcodes.ALOAD, stateLocal);
    super.visitMethodInsn(Opcodes.INVOKESTATIC, TASK_BLOCK_HELPER, "finish", FINISH_DESC, false);
    super.visitJumpInsn(Opcodes.GOTO, end);

    super.visitLabel(tryEnd);
    super.visitLabel(handler);
    visitNewLocalVarInsn(Opcodes.ASTORE, throwableLocal);
    visitNewLocalVarInsn(Opcodes.ALOAD, stateLocal);
    super.visitMethodInsn(Opcodes.INVOKESTATIC, TASK_BLOCK_HELPER, "finish", FINISH_DESC, false);
    visitNewLocalVarInsn(Opcodes.ALOAD, throwableLocal);
    super.visitInsn(Opcodes.ATHROW);

    super.visitLabel(end);
  }

  /**
   * Emits the wrapped form for {@code Thread.sleep(java.time.Duration)V}. The incoming stack has
   * one object reference (the {@code Duration}); we ASTORE it, call captureForSleep, then ALOAD
   * before the original call, mirroring the LSTORE/LLOAD pattern used for primitive overloads.
   */
  private void emitWrappedDurationSleepCall(
      final String owner, final String name, final boolean isInterface) {
    final int tmpDuration = newLocal(DURATION_TYPE);
    final int stateLocal = newLocal(STATE_TYPE);
    final int throwableLocal = newLocal(THROWABLE_TYPE);

    final Label tryStart = new Label();
    final Label tryEnd = new Label();
    final Label handler = new Label();
    final Label end = new Label();

    super.visitTryCatchBlock(tryStart, tryEnd, handler, null);

    // Pop the Duration reference into a local.
    visitNewLocalVarInsn(Opcodes.ASTORE, tmpDuration);

    // captureForSleep() is null whenever the integration or native entry rejects the interval.
    super.visitMethodInsn(
        Opcodes.INVOKESTATIC, TASK_BLOCK_HELPER, "captureForSleep", CAPTURE_FOR_SLEEP_DESC, false);
    visitNewLocalVarInsn(Opcodes.ASTORE, stateLocal);

    // Protected region: the original Thread.sleep(Duration) call.
    super.visitLabel(tryStart);
    visitNewLocalVarInsn(Opcodes.ALOAD, tmpDuration);
    super.visitMethodInsn(Opcodes.INVOKESTATIC, owner, name, SLEEP_DURATION_DESC, isInterface);
    visitNewLocalVarInsn(Opcodes.ALOAD, stateLocal);
    super.visitMethodInsn(Opcodes.INVOKESTATIC, TASK_BLOCK_HELPER, "finish", FINISH_DESC, false);
    super.visitJumpInsn(Opcodes.GOTO, end);

    // Exception handler: finish first, then rethrow.
    super.visitLabel(tryEnd);
    super.visitLabel(handler);
    visitNewLocalVarInsn(Opcodes.ASTORE, throwableLocal);
    visitNewLocalVarInsn(Opcodes.ALOAD, stateLocal);
    super.visitMethodInsn(Opcodes.INVOKESTATIC, TASK_BLOCK_HELPER, "finish", FINISH_DESC, false);
    visitNewLocalVarInsn(Opcodes.ALOAD, throwableLocal);
    super.visitInsn(Opcodes.ATHROW);

    super.visitLabel(end);
  }

  private void visitNewLocalVarInsn(final int opcode, final int local) {
    mv.visitVarInsn(opcode, local);
  }
}
