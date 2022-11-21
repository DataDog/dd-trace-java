package datadog.trace.agent.tooling.bytebuddy;

import datadog.trace.api.InstrumenterConfig;
import datadog.trace.bootstrap.ExceptionLogger;
import net.bytebuddy.ClassFileVersion;
import net.bytebuddy.asm.Advice.ExceptionHandler;
import net.bytebuddy.implementation.Implementation;
import net.bytebuddy.implementation.bytecode.StackManipulation;
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

  private static final ExceptionHandler EXCEPTION_STACK_HANDLER =
      new ExceptionHandler.Simple(
          new StackManipulation() {
            // Pops one Throwable off the stack. Maxes the stack to at least 3.
            private final Size size = new StackManipulation.Size(-1, 3);

            @Override
            public boolean isValid() {
              return true;
            }

            @Override
            public Size apply(final MethodVisitor mv, final Implementation.Context context) {
              final String name = context.getInstrumentedType().getName();
              final boolean exitOnFailure = InstrumenterConfig.get().isInternalExitOnFailure();
              final String logMethod = exitOnFailure ? "error" : "debug";

              // Writes the following bytecode if exitOnFailure is false:
              //
              // try {
              //   org.slf4j.LoggerFactory.getLogger((Class)ExceptionLogger.class)
              //     .debug("Failed to handle exception in instrumentation for ...", t);
              // } catch (Throwable t2) {
              // }
              //
              // And the following bytecode if exitOnFailure is true:
              //
              // try {
              //   org.slf4j.LoggerFactory.getLogger((Class)ExceptionLogger.class)
              //     .error("Failed to handle exception in instrumentation for ...", t);
              //   System.exit(1);
              // } catch (Throwable t2) {
              // }
              //
              final Label logStart = new Label();
              final Label logEnd = new Label();
              final Label eatException = new Label();
              final Label handlerExit = new Label();

              // Frames are only meaningful for class files in version 6 or later.
              final boolean frames =
                  context.getClassFileVersion().isAtLeast(ClassFileVersion.JAVA_V6);

              mv.visitTryCatchBlock(logStart, logEnd, eatException, "java/lang/Throwable");

              // stack: (top) throwable
              mv.visitLabel(logStart);
              mv.visitLdcInsn(Type.getType("L" + HANDLER_NAME + ";"));
              mv.visitMethodInsn(
                  Opcodes.INVOKESTATIC,
                  LOG_FACTORY_NAME,
                  "getLogger",
                  "(Ljava/lang/Class;)L" + LOGGER_NAME + ";",
                  false);
              mv.visitInsn(Opcodes.SWAP); // stack: (top) throwable,logger
              mv.visitLdcInsn("Failed to handle exception in instrumentation for " + name);
              mv.visitInsn(Opcodes.SWAP); // stack: (top) throwable,string,logger
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
          });

  public static ExceptionHandler defaultExceptionHandler() {
    return EXCEPTION_STACK_HANDLER;
  }
}
