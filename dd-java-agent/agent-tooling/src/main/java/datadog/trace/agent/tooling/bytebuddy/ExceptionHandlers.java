package datadog.trace.agent.tooling.bytebuddy;

import datadog.trace.api.InstrumenterConfig;
import datadog.trace.api.ProductActivation;
import datadog.trace.bootstrap.ExceptionLogger;
import net.bytebuddy.ClassFileVersion;
import net.bytebuddy.asm.Advice.ExceptionHandler;
import net.bytebuddy.implementation.Implementation;
import net.bytebuddy.implementation.bytecode.StackManipulation;
import net.bytebuddy.implementation.bytecode.constant.TextConstant;
import net.bytebuddy.jar.asm.Label;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;
import net.bytebuddy.jar.asm.Type;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ExceptionHandlers {
  private static final String LOG_FACTORY_NAME = LoggerFactory.class.getName().replace('.', '/');
  private static final String LOGGER_NAME = Logger.class.getName().replace('.', '/');
  // Bootstrap ExceptionHandler.class will always be resolvable, so we'll use it in the log name
  private static final String HANDLER_NAME = ExceptionLogger.class.getName().replace('.', '/');

  // Shared bytecode that turns the [throwable, adviceName] stack left by the TextConstant
  // prefix into a logged + optionally swallowed exception.
  private static final StackManipulation EXCEPTION_STACK_MANIPULATION =
      new StackManipulation() {
        // Pops the throwable and the advice-name String off the stack.
        // Peak growth above the pair on entry is +2 (during message-building LDCs).
        private final Size size = new StackManipulation.Size(-2, 2);
        private final boolean appSecEnabled =
            InstrumenterConfig.get().getAppSecActivation() != ProductActivation.FULLY_DISABLED;
        private final boolean detailedErrors =
            InstrumenterConfig.get().isDetailedInstrumentationErrors();

        @Override
        public boolean isValid() {
          return true;
        }

        @Override
        public Size apply(final MethodVisitor mv, final Implementation.Context context) {
          final String instrumentedTypeName = context.getInstrumentedType().getName();
          final boolean exitOnFailure = InstrumenterConfig.get().isInternalExitOnFailure();
          final String logMethod = exitOnFailure ? "error" : "debug";

          // On entry the stack is: (bottom) throwable, adviceName (top)
          // — adviceName was pushed by the preceding TextConstant.
          //
          // Emits the following Java-equivalent code when exitOnFailure is false:
          //
          // BlockingExceptionHandler.rethrowIfBlockingException(t);
          // try {
          //   InstrumentationErrors.recordError();
          //   org.slf4j.LoggerFactory.getLogger((Class) ExceptionLogger.class)
          //     .debug("Failed to handle exception in instrumentation for <type> (" + adviceName +
          // ")", t);
          // } catch (Throwable t2) {
          // }
          //
          // and the same with .error(...) followed by System.exit(1) when exitOnFailure is true.
          final Label logStart = new Label();
          final Label logEnd = new Label();
          final Label eatException = new Label();
          final Label handlerExit = new Label();

          // Frames are only meaningful for class files in version 6 or later.
          final boolean frames = context.getClassFileVersion().isAtLeast(ClassFileVersion.JAVA_V6);

          if (appSecEnabled) {
            // Need throwable on top for rethrowIfBlockingException.
            // stack: (top) adviceName, throwable -> top throwable
            mv.visitInsn(Opcodes.SWAP);
            mv.visitMethodInsn(
                Opcodes.INVOKESTATIC,
                "datadog/trace/bootstrap/blocking/BlockingExceptionHandler",
                "rethrowIfBlockingException",
                "(Ljava/lang/Throwable;)Ljava/lang/Throwable;",
                false);
            // restore: (top) throwable, adviceName -> top adviceName
            mv.visitInsn(Opcodes.SWAP);
          }

          mv.visitTryCatchBlock(logStart, logEnd, eatException, "java/lang/Throwable");
          mv.visitLabel(logStart);
          // record instrumentation error
          if (detailedErrors) {
            // recordError(Throwable) needs throwable on top, then we restore.
            mv.visitInsn(Opcodes.SWAP);
            mv.visitMethodInsn(
                Opcodes.INVOKESTATIC,
                "datadog/trace/bootstrap/InstrumentationErrors",
                "recordError",
                "(Ljava/lang/Throwable;)Ljava/lang/Throwable;",
                false);
            mv.visitInsn(Opcodes.SWAP);
          } else {
            mv.visitMethodInsn(
                Opcodes.INVOKESTATIC,
                "datadog/trace/bootstrap/InstrumentationErrors",
                "recordError",
                "()V",
                false);
          }

          // Build the log message:
          //   "Failed to handle exception in instrumentation for <type> - " + adviceName
          // <type> is a generation-time constant baked into the prefix LDC.
          // stack: (top) adviceName, throwable
          mv.visitLdcInsn(
              "Failed to handle exception in instrumentation for " + instrumentedTypeName + " - ");
          // stack: prefix (top), adviceName, throwable
          mv.visitInsn(Opcodes.SWAP);
          // stack: adviceName (top), prefix, throwable
          mv.visitMethodInsn(
              Opcodes.INVOKEVIRTUAL,
              "java/lang/String",
              "concat",
              "(Ljava/lang/String;)Ljava/lang/String;",
              false);
          // stack: message (top), throwable
          mv.visitInsn(Opcodes.SWAP);
          // stack: throwable(top), message

          mv.visitLdcInsn(Type.getType("L" + HANDLER_NAME + ";"));
          mv.visitMethodInsn(
              Opcodes.INVOKESTATIC,
              LOG_FACTORY_NAME,
              "getLogger",
              "(Ljava/lang/Class;)L" + LOGGER_NAME + ";",
              false);
          // stack: logger (top), throwable, message
          mv.visitInsn(Opcodes.DUP_X2);
          // stack: logger (top), throwable, message, logger
          mv.visitInsn(Opcodes.POP);
          // stack: throwable (top), message, logger
          mv.visitMethodInsn(
              Opcodes.INVOKEINTERFACE,
              LOGGER_NAME,
              logMethod,
              "(Ljava/lang/String;Ljava/lang/Throwable;)V",
              true);

          if (exitOnFailure) {
            mv.visitInsn(Opcodes.ICONST_1);
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/System", "exit", "(I)V", false);
          }
          mv.visitLabel(logEnd);
          mv.visitJumpInsn(Opcodes.GOTO, handlerExit);

          // if the runtime can't reach our ExceptionHandler or logger,
          //   silently eat the exception
          mv.visitLabel(eatException);
          if (frames) {
            mv.visitFrame(Opcodes.F_SAME1, 0, null, 1, new Object[] {"java/lang/Throwable"});
          }
          mv.visitInsn(Opcodes.POP);
          // mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Throwable",
          //    "printStackTrace", "()V", false);

          mv.visitLabel(handlerExit);
          if (frames) {
            mv.visitFrame(Opcodes.F_SAME, 0, null, 0, null);
          }

          return size;
        }
      };

  public static ExceptionHandler exceptionHandlerFor(final String adviceClassName) {
    return new ExceptionHandler.Simple(
        new StackManipulation.Compound(
            new TextConstant(adviceClassName), EXCEPTION_STACK_MANIPULATION));
  }
}
