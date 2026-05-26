package datadog.trace.instrumentation.threadsleep;

import datadog.trace.agent.tooling.bytebuddy.reqctx.LocalVariablesSorter;
import net.bytebuddy.jar.asm.Label;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;
import net.bytebuddy.jar.asm.Type;
import net.bytebuddy.utility.OpenedClassReader;

/**
 * Wraps each {@code INVOKESTATIC java/lang/Thread.sleep(...)V} call site in a TaskBlock
 * capture/finish pair with a try-finally so the {@code finish} runs even when {@code Thread.sleep}
 * throws {@code InterruptedException}.
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
 * <p>{@code Thread.sleep(java.time.Duration)V} (JDK 19+) is intentionally <em>not</em> rewritten:
 * the Duration overload internally calls {@code Thread.sleep(J,I)V} which we already cover, so a
 * second wrap would create a double-bracket. {@link NoDoubleBracketTest} guards against this.
 */
final class ThreadSleepCallSiteMethodVisitor extends LocalVariablesSorter {

  static final String THREAD_INTERNAL = "java/lang/Thread";
  static final String STATE_INTERNAL =
      "datadog/trace/bootstrap/instrumentation/java/concurrent/TaskBlockHelper$State";
  static final String STATE_DESC = "L" + STATE_INTERNAL + ";";
  static final String TASK_BLOCK_HELPER =
      "datadog/trace/bootstrap/instrumentation/java/concurrent/TaskBlockHelper";
  static final String CAPTURE_FOR_SLEEP_DESC = "()" + STATE_DESC;
  static final String FINISH_DESC = "(" + STATE_DESC + ")V";

  static final String SLEEP_J_DESC = "(J)V";
  static final String SLEEP_JI_DESC = "(JI)V";

  private static final Type LONG_TYPE = Type.LONG_TYPE;
  private static final Type INT_TYPE = Type.INT_TYPE;
  private static final Type STATE_TYPE = Type.getObjectType(STATE_INTERNAL);
  private static final Type THROWABLE_TYPE = Type.getObjectType("java/lang/Throwable");

  ThreadSleepCallSiteMethodVisitor(
      final int access, final String descriptor, final MethodVisitor delegate) {
    super(OpenedClassReader.ASM_API, access, descriptor, delegate);
  }

  @Override
  public void visitMethodInsn(
      final int opcode,
      final String owner,
      final String name,
      final String descriptor,
      final boolean isInterface) {
    if (opcode == Opcodes.INVOKESTATIC
        && THREAD_INTERNAL.equals(owner)
        && "sleep".equals(name)
        && (SLEEP_J_DESC.equals(descriptor) || SLEEP_JI_DESC.equals(descriptor))) {
      emitWrappedSleepCall(owner, name, descriptor, isInterface);
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

    // captureForSleep() — null when no active span (TaskBlockHelper fast-path).
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
    // case (no active span at capture) is harmless.
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
