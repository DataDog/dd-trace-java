package datadog.trace.agent.tooling;

import static net.bytebuddy.utility.OpenedClassReader.ASM_API;

import java.security.ProtectionDomain;
import java.util.List;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.asm.AsmVisitorWrapper;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.implementation.Implementation;
import net.bytebuddy.implementation.bytecode.assign.Assigner;
import net.bytebuddy.jar.asm.Handle;
import net.bytebuddy.jar.asm.Label;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.pool.TypePool;
import net.bytebuddy.utility.JavaModule;

/** Adds method and advice context to Byte Buddy advice application failures in debug mode. */
final class DebuggingAdviceTransformer extends AgentBuilder.Transformer.ForAdvice {
  private final String instrumentationClass;
  private final String adviceClass;

  DebuggingAdviceTransformer(
      Advice.WithCustomMapping advice, String instrumentationClass, String adviceClass) {
    super(advice);
    this.instrumentationClass = instrumentationClass;
    this.adviceClass = adviceClass;
  }

  private DebuggingAdviceTransformer(
      Advice.WithCustomMapping advice,
      Advice.ExceptionHandler exceptionHandler,
      Assigner assigner,
      ClassFileLocator classFileLocator,
      AgentBuilder.PoolStrategy poolStrategy,
      AgentBuilder.LocationStrategy locationStrategy,
      List<AgentBuilder.Transformer.ForAdvice.Entry> entries,
      List<String> auxiliaries,
      String instrumentationClass,
      String adviceClass) {
    super(
        advice,
        exceptionHandler,
        assigner,
        classFileLocator,
        poolStrategy,
        locationStrategy,
        entries,
        auxiliaries);
    this.instrumentationClass = instrumentationClass;
    this.adviceClass = adviceClass;
  }

  @Override
  protected AgentBuilder.Transformer.ForAdvice make(
      Advice.WithCustomMapping advice,
      Advice.ExceptionHandler exceptionHandler,
      Assigner assigner,
      ClassFileLocator classFileLocator,
      AgentBuilder.PoolStrategy poolStrategy,
      AgentBuilder.LocationStrategy locationStrategy,
      List<AgentBuilder.Transformer.ForAdvice.Entry> entries,
      List<String> auxiliaries) {
    return new DebuggingAdviceTransformer(
        advice,
        exceptionHandler,
        assigner,
        classFileLocator,
        poolStrategy,
        locationStrategy,
        entries,
        auxiliaries,
        instrumentationClass,
        adviceClass);
  }

  @Override
  protected AsmVisitorWrapper.ForDeclaredMethods.MethodVisitorWrapper wrap(
      TypeDescription target,
      ClassLoader classLoader,
      JavaModule module,
      ProtectionDomain protectionDomain,
      Advice advice) {
    return wrap(
        super.wrap(target, classLoader, module, protectionDomain, advice),
        instrumentationClass,
        adviceClass);
  }

  static AsmVisitorWrapper.ForDeclaredMethods.MethodVisitorWrapper wrap(
      AsmVisitorWrapper.ForDeclaredMethods.MethodVisitorWrapper delegate,
      String instrumentationClass,
      String adviceClass) {
    return new DebuggingMethodVisitorWrapper(delegate, instrumentationClass, adviceClass);
  }

  private static final class DebuggingMethodVisitorWrapper
      implements AsmVisitorWrapper.ForDeclaredMethods.MethodVisitorWrapper {
    private final AsmVisitorWrapper.ForDeclaredMethods.MethodVisitorWrapper delegate;
    private final String instrumentationClass;
    private final String adviceClass;

    private DebuggingMethodVisitorWrapper(
        AsmVisitorWrapper.ForDeclaredMethods.MethodVisitorWrapper delegate,
        String instrumentationClass,
        String adviceClass) {
      this.delegate = delegate;
      this.instrumentationClass = instrumentationClass;
      this.adviceClass = adviceClass;
    }

    @Override
    public MethodVisitor wrap(
        TypeDescription instrumentedType,
        MethodDescription instrumentedMethod,
        MethodVisitor methodVisitor,
        Implementation.Context implementationContext,
        TypePool typePool,
        int writerFlags,
        int readerFlags) {
      DownstreamMethodVisitor downstreamVisitor = new DownstreamMethodVisitor(methodVisitor);
      try {
        MethodVisitor adviceVisitor =
            delegate.wrap(
                instrumentedType,
                instrumentedMethod,
                downstreamVisitor,
                implementationContext,
                typePool,
                writerFlags,
                readerFlags);
        return adviceVisitor == null
            ? null
            : new DebuggingMethodVisitor(
                adviceVisitor,
                instrumentationClass,
                adviceClass,
                instrumentedType,
                instrumentedMethod,
                downstreamVisitor);
      } catch (RuntimeException | LinkageError failure) {
        throw recordAdviceFailure(
            instrumentationClass,
            adviceClass,
            instrumentedType,
            instrumentedMethod,
            downstreamVisitor,
            failure);
      }
    }
  }

  private static RuntimeException recordAdviceFailure(
      String instrumentationClass,
      String adviceClass,
      TypeDescription instrumentedType,
      MethodDescription instrumentedMethod,
      DownstreamMethodVisitor downstreamVisitor,
      Throwable failure) {
    if (downstreamVisitor.clear(failure)) {
      return propagate(failure);
    }
    return AdviceTransformationException.wrap(
        instrumentationClass, adviceClass, instrumentedType, instrumentedMethod, failure);
  }

  private static RuntimeException propagate(Throwable failure) {
    if (failure instanceof RuntimeException) {
      return (RuntimeException) failure;
    }
    throw (LinkageError) failure;
  }

  /** Marks failures crossing the visitor boundary below advice so they keep their attribution. */
  private static final class DownstreamMethodVisitor extends FailureTrackingMethodVisitor {
    private Throwable failure;

    private DownstreamMethodVisitor(MethodVisitor delegate) {
      super(delegate);
    }

    @Override
    RuntimeException record(Throwable failure) {
      this.failure = failure;
      return propagate(failure);
    }

    private boolean clear(Throwable failure) {
      Throwable downstreamFailure = this.failure;
      this.failure = null;
      return downstreamFailure == failure;
    }
  }

  /**
   * Advice binding is triggered by the first code event after the exception table. Forwarding all
   * code events here attributes failures thrown during that binding to the exact target method.
   */
  private static final class DebuggingMethodVisitor extends FailureTrackingMethodVisitor {
    private final String instrumentationClass;
    private final String adviceClass;
    private final TypeDescription instrumentedType;
    private final MethodDescription instrumentedMethod;
    private final DownstreamMethodVisitor downstreamVisitor;

    private DebuggingMethodVisitor(
        MethodVisitor delegate,
        String instrumentationClass,
        String adviceClass,
        TypeDescription instrumentedType,
        MethodDescription instrumentedMethod,
        DownstreamMethodVisitor downstreamVisitor) {
      super(delegate);
      this.instrumentationClass = instrumentationClass;
      this.adviceClass = adviceClass;
      this.instrumentedType = instrumentedType;
      this.instrumentedMethod = instrumentedMethod;
      this.downstreamVisitor = downstreamVisitor;
    }

    @Override
    RuntimeException record(Throwable failure) {
      return recordAdviceFailure(
          instrumentationClass,
          adviceClass,
          instrumentedType,
          instrumentedMethod,
          downstreamVisitor,
          failure);
    }
  }

  private abstract static class FailureTrackingMethodVisitor extends MethodVisitor {

    private FailureTrackingMethodVisitor(MethodVisitor delegate) {
      super(ASM_API, delegate);
    }

    abstract RuntimeException record(Throwable failure);

    @Override
    public void visitCode() {
      try {
        super.visitCode();
      } catch (RuntimeException | LinkageError failure) {
        throw record(failure);
      }
    }

    @Override
    public void visitFrame(int type, int numLocal, Object[] local, int numStack, Object[] stack) {
      try {
        super.visitFrame(type, numLocal, local, numStack, stack);
      } catch (RuntimeException | LinkageError failure) {
        throw record(failure);
      }
    }

    @Override
    public void visitInsn(int opcode) {
      try {
        super.visitInsn(opcode);
      } catch (RuntimeException | LinkageError failure) {
        throw record(failure);
      }
    }

    @Override
    public void visitIntInsn(int opcode, int operand) {
      try {
        super.visitIntInsn(opcode, operand);
      } catch (RuntimeException | LinkageError failure) {
        throw record(failure);
      }
    }

    @Override
    public void visitVarInsn(int opcode, int varIndex) {
      try {
        super.visitVarInsn(opcode, varIndex);
      } catch (RuntimeException | LinkageError failure) {
        throw record(failure);
      }
    }

    @Override
    public void visitTypeInsn(int opcode, String type) {
      try {
        super.visitTypeInsn(opcode, type);
      } catch (RuntimeException | LinkageError failure) {
        throw record(failure);
      }
    }

    @Override
    public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
      try {
        super.visitFieldInsn(opcode, owner, name, descriptor);
      } catch (RuntimeException | LinkageError failure) {
        throw record(failure);
      }
    }

    @Override
    public void visitMethodInsn(
        int opcode, String owner, String name, String descriptor, boolean isInterface) {
      try {
        super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
      } catch (RuntimeException | LinkageError failure) {
        throw record(failure);
      }
    }

    @Override
    public void visitInvokeDynamicInsn(
        String name, String descriptor, Handle bootstrapMethodHandle, Object... arguments) {
      try {
        super.visitInvokeDynamicInsn(name, descriptor, bootstrapMethodHandle, arguments);
      } catch (RuntimeException | LinkageError failure) {
        throw record(failure);
      }
    }

    @Override
    public void visitJumpInsn(int opcode, Label label) {
      try {
        super.visitJumpInsn(opcode, label);
      } catch (RuntimeException | LinkageError failure) {
        throw record(failure);
      }
    }

    @Override
    public void visitLabel(Label label) {
      try {
        super.visitLabel(label);
      } catch (RuntimeException | LinkageError failure) {
        throw record(failure);
      }
    }

    @Override
    public void visitLdcInsn(Object value) {
      try {
        super.visitLdcInsn(value);
      } catch (RuntimeException | LinkageError failure) {
        throw record(failure);
      }
    }

    @Override
    public void visitIincInsn(int varIndex, int increment) {
      try {
        super.visitIincInsn(varIndex, increment);
      } catch (RuntimeException | LinkageError failure) {
        throw record(failure);
      }
    }

    @Override
    public void visitTableSwitchInsn(int min, int max, Label defaultLabel, Label... labels) {
      try {
        super.visitTableSwitchInsn(min, max, defaultLabel, labels);
      } catch (RuntimeException | LinkageError failure) {
        throw record(failure);
      }
    }

    @Override
    public void visitLookupSwitchInsn(Label defaultLabel, int[] keys, Label[] labels) {
      try {
        super.visitLookupSwitchInsn(defaultLabel, keys, labels);
      } catch (RuntimeException | LinkageError failure) {
        throw record(failure);
      }
    }

    @Override
    public void visitMultiANewArrayInsn(String descriptor, int dimensions) {
      try {
        super.visitMultiANewArrayInsn(descriptor, dimensions);
      } catch (RuntimeException | LinkageError failure) {
        throw record(failure);
      }
    }

    @Override
    public void visitTryCatchBlock(Label start, Label end, Label handler, String type) {
      try {
        super.visitTryCatchBlock(start, end, handler, type);
      } catch (RuntimeException | LinkageError failure) {
        throw record(failure);
      }
    }

    @Override
    public void visitLocalVariable(
        String name, String descriptor, String signature, Label start, Label end, int index) {
      try {
        super.visitLocalVariable(name, descriptor, signature, start, end, index);
      } catch (RuntimeException | LinkageError failure) {
        throw record(failure);
      }
    }

    @Override
    public void visitLineNumber(int line, Label start) {
      try {
        super.visitLineNumber(line, start);
      } catch (RuntimeException | LinkageError failure) {
        throw record(failure);
      }
    }

    @Override
    public void visitMaxs(int maxStack, int maxLocals) {
      try {
        super.visitMaxs(maxStack, maxLocals);
      } catch (RuntimeException | LinkageError failure) {
        throw record(failure);
      }
    }

    @Override
    public void visitEnd() {
      try {
        super.visitEnd();
      } catch (RuntimeException | LinkageError failure) {
        throw record(failure);
      }
    }
  }

  static final class AdviceTransformationException extends RuntimeException {
    private final String instrumentationClass;
    private final String adviceClass;
    private final String targetClass;
    private final String targetMethod;

    private AdviceTransformationException(
        String instrumentationClass,
        String adviceClass,
        String targetClass,
        String targetMethod,
        Throwable cause) {
      super("Advice transformation failed for " + targetClass + '.' + targetMethod, cause);
      this.instrumentationClass = instrumentationClass;
      this.adviceClass = adviceClass;
      this.targetClass = targetClass;
      this.targetMethod = targetMethod;
    }

    private static RuntimeException wrap(
        String instrumentationClass,
        String adviceClass,
        TypeDescription instrumentedType,
        MethodDescription instrumentedMethod,
        Throwable failure) {
      if (failure instanceof AdviceTransformationException) {
        return (AdviceTransformationException) failure;
      }
      return new AdviceTransformationException(
          instrumentationClass,
          adviceClass,
          instrumentedType.getName(),
          instrumentedMethod.getInternalName() + instrumentedMethod.getDescriptor(),
          failure);
    }

    String getInstrumentationClass() {
      return instrumentationClass;
    }

    String getAdviceClass() {
      return adviceClass;
    }

    String getTargetClass() {
      return targetClass;
    }

    String getTargetMethod() {
      return targetMethod;
    }
  }
}
