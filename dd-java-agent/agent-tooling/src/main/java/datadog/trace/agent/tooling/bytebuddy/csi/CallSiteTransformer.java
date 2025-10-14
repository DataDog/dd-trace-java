package datadog.trace.agent.tooling.bytebuddy.csi;

import static datadog.trace.agent.tooling.csi.CallSiteAdvice.AdviceType.AFTER;
import static datadog.trace.api.telemetry.LogCollector.SEND_TELEMETRY;
import static net.bytebuddy.jar.asm.ClassWriter.COMPUTE_MAXS;

import datadog.trace.agent.tooling.HelperInjector;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.csi.CallSiteAdvice;
import datadog.trace.agent.tooling.csi.CallSiteAdvice.StackDupMode;
import datadog.trace.agent.tooling.csi.InvokeAdvice;
import datadog.trace.agent.tooling.csi.InvokeDynamicAdvice;
import java.security.ProtectionDomain;
import java.util.Deque;
import java.util.LinkedList;
import javax.annotation.Nonnull;
import net.bytebuddy.asm.AsmVisitorWrapper;
import net.bytebuddy.description.field.FieldDescription;
import net.bytebuddy.description.field.FieldList;
import net.bytebuddy.description.method.MethodList;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.implementation.Implementation;
import net.bytebuddy.jar.asm.ClassVisitor;
import net.bytebuddy.jar.asm.Handle;
import net.bytebuddy.jar.asm.Label;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;
import net.bytebuddy.jar.asm.Type;
import net.bytebuddy.pool.TypePool;
import net.bytebuddy.utility.JavaModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CallSiteTransformer implements Instrumenter.TransformingAdvice {

  private static Logger LOGGER = LoggerFactory.getLogger(CallSiteTransformer.class);

  private static final Instrumenter.TransformingAdvice NO_OP =
      (builder, typeDescription, classLoader, module, pd) -> builder;

  public static final int ASM_API = Opcodes.ASM8;

  private final Advices advices;

  private final Instrumenter.TransformingAdvice helperInjector;

  public CallSiteTransformer(@Nonnull final Advices advices) {
    this("call-site-transformer", advices);
  }

  public CallSiteTransformer(@Nonnull final String name, @Nonnull final Advices advices) {
    this.advices = advices;
    final String[] helpers = advices.getHelpers();
    this.helperInjector =
        helpers == null || helpers.length == 0
            ? NO_OP
            : new HelperInjector(false, name, advices.getHelpers());
  }

  @Override
  public @Nonnull DynamicType.Builder<?> transform(
      @Nonnull final DynamicType.Builder<?> builder,
      @Nonnull final TypeDescription type,
      final ClassLoader classLoader,
      final JavaModule module,
      final ProtectionDomain pd) {
    Advices discovered = advices.findAdvices(builder, type, classLoader);
    if (discovered.isEmpty()) {
      return builder;
    }
    final DynamicType.Builder<?> withHelpers =
        helperInjector.transform(builder, type, classLoader, module, pd);
    return withHelpers.visit(new CallSiteVisitorWrapper(discovered));
  }

  private static class CallSiteVisitorWrapper extends AsmVisitorWrapper.AbstractBase {

    private final Advices advices;

    private CallSiteVisitorWrapper(@Nonnull final Advices advices) {
      this.advices = advices;
    }

    @Override
    public int mergeWriter(final int flags) {
      return flags | COMPUTE_MAXS;
    }

    @Override
    public @Nonnull ClassVisitor wrap(
        @Nonnull final TypeDescription instrumentedType,
        @Nonnull final ClassVisitor classVisitor,
        @Nonnull final Implementation.Context implementationContext,
        @Nonnull final TypePool typePool,
        @Nonnull final FieldList<FieldDescription.InDefinedShape> fields,
        @Nonnull final MethodList<?> methods,
        final int writerFlags,
        final int readerFlags) {
      return new CallSiteClassVisitor(advices, classVisitor);
    }
  }

  private static class CallSiteClassVisitor extends ClassVisitor {

    private final Advices advices;

    private CallSiteClassVisitor(
        @Nonnull final Advices advices, @Nonnull final ClassVisitor delegated) {
      super(ASM_API, delegated);
      this.advices = advices;
    }

    @Override
    public MethodVisitor visitMethod(
        final int access,
        final String name,
        final String descriptor,
        final String signature,
        final String[] exceptions) {
      final MethodVisitor delegated =
          super.visitMethod(access, name, descriptor, signature, exceptions);
      return "<init>".equals(name)
          ? new CallSiteCtorMethodVisitor(advices, delegated)
          : new CallSiteMethodVisitor(advices, delegated);
    }
  }

  private static class CallSiteMethodVisitor extends MethodVisitor
      implements CallSiteAdvice.MethodHandler {
    protected final Advices advices;
    protected int lastOpcode;
    protected boolean newFollowedByDup;

    private CallSiteMethodVisitor(
        @Nonnull final Advices advices, @Nonnull final MethodVisitor delegated) {
      super(ASM_API, delegated);
      this.advices = advices;
    }

    @Override
    public void visitTypeInsn(int opcode, String type) {
      lastOpcode = opcode;
      if (opcode == Opcodes.NEW) {
        newFollowedByDup = false;
      }
      super.visitTypeInsn(opcode, type);
    }

    @Override
    public void visitInsn(final int opcode) {
      if (opcode == Opcodes.DUP) {
        newFollowedByDup = lastOpcode == Opcodes.NEW;
      }
      lastOpcode = opcode;
      super.visitInsn(opcode);
    }

    @Override
    public void visitMethodInsn(
        final int opcode,
        final String owner,
        final String name,
        final String descriptor,
        final boolean isInterface) {
      lastOpcode = opcode;
      CallSiteAdvice advice = advices.findAdvice(owner, name, descriptor);
      if (applyInvokeAdvice(advice, name, descriptor)) {
        invokeAdvice((InvokeAdvice) advice, opcode, owner, name, descriptor, isInterface);
      } else {
        mv.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
      }
    }

    protected void invokeAdvice(
        final InvokeAdvice advice,
        final int opcode,
        final String owner,
        final String name,
        final String descriptor,
        final boolean isInterface) {
      advice.apply(this, opcode, owner, name, descriptor, isInterface);
    }

    @Override
    public void visitInvokeDynamicInsn(
        final String name,
        final String descriptor,
        final Handle bootstrapMethodHandle,
        final Object... bootstrapMethodArguments) {
      lastOpcode = Opcodes.INVOKEDYNAMIC;
      CallSiteAdvice advice = advices.findAdvice(bootstrapMethodHandle);
      if (advice instanceof InvokeDynamicAdvice) {
        invokeDynamicAdvice(
            (InvokeDynamicAdvice) advice,
            name,
            descriptor,
            bootstrapMethodHandle,
            bootstrapMethodArguments);
      } else {
        mv.visitInvokeDynamicInsn(
            name, descriptor, bootstrapMethodHandle, bootstrapMethodArguments);
      }
    }

    protected void invokeDynamicAdvice(
        final InvokeDynamicAdvice advice,
        final String name,
        final String descriptor,
        final Handle bootstrapMethodHandle,
        final Object... bootstrapMethodArguments) {
      advice.apply(this, name, descriptor, bootstrapMethodHandle, bootstrapMethodArguments);
    }

    @Override
    public void instruction(final int opcode) {
      mv.visitInsn(opcode);
    }

    @Override
    public void instruction(final int opcode, final int parameter) {
      mv.visitIntInsn(opcode, parameter);
    }

    @Override
    public void instruction(final int opcode, final String type) {
      mv.visitTypeInsn(opcode, type);
    }

    @Override
    public void loadConstant(final Object constant) {
      mv.visitLdcInsn(constant);
    }

    @Override
    public void loadConstantArray(final Object[] array) {
      CallSiteUtils.pushConstantArray(mv, array);
    }

    @Override
    public void field(
        final int opcode, final String owner, final String field, final String descriptor) {
      mv.visitFieldInsn(opcode, owner, field, descriptor);
    }

    @Override
    public void method(
        final int opcode,
        final String owner,
        final String name,
        final String descriptor,
        final boolean isInterface) {
      mv.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
    }

    @Override
    public void advice(String owner, String name, String descriptor) {
      mv.visitMethodInsn(Opcodes.INVOKESTATIC, owner, name, descriptor, false);
    }

    @Override
    public void invokeDynamic(
        final String name,
        final String descriptor,
        final Handle bootstrapMethodHandle,
        final Object... bootstrapMethodArguments) {
      mv.visitInvokeDynamicInsn(name, descriptor, bootstrapMethodHandle, bootstrapMethodArguments);
    }

    @Override
    public void dupParameters(final String methodDescriptor, final StackDupMode mode) {
      final Type method = Type.getMethodType(methodDescriptor);
      if (method.getArgumentTypes().length == 0) {
        return;
      }
      CallSiteUtils.dup(mv, method.getArgumentTypes(), mode);
    }

    @Override
    public void dupParameters(final String methodDescriptor, int[] indices, String owner) {
      final Type method = Type.getMethodType(methodDescriptor);
      Type[] stackArgTypes;
      if (owner != null) {
        stackArgTypes = methodParamTypesWithThis(owner, methodDescriptor);
        int[] newIndices = new int[indices.length];
        for (int i = 0; i < indices.length; i++) {
          newIndices[i] = indices[i] + 1;
        }
        indices = newIndices;
      } else {
        stackArgTypes = method.getArgumentTypes();
      }
      CallSiteUtils.dup(mv, stackArgTypes, indices);
    }

    @Override
    public void dupInvoke(
        final String owner, final String methodDescriptor, final StackDupMode mode) {
      final Type[] parameters = methodParamTypesWithThis(owner, methodDescriptor);
      CallSiteUtils.dup(mv, parameters, mode);
    }

    @Override
    public void dupInvoke(String owner, String methodDescriptor, int[] parameterIndices) {
      final Type[] methodParameterTypesWithThis = methodParamTypesWithThis(owner, methodDescriptor);

      int[] parameterIndicesWithThis = new int[parameterIndices.length + 1];
      parameterIndicesWithThis[0] = 0;
      for (int i = 0; i < parameterIndices.length; i++) {
        parameterIndicesWithThis[i + 1] = parameterIndices[i] + 1;
      }
      CallSiteUtils.dup(mv, methodParameterTypesWithThis, parameterIndicesWithThis);
    }

    private Type[] methodParamTypesWithThis(String owner, String methodDescriptor) {
      final Type method = Type.getMethodType(methodDescriptor);
      Type ownerType = Type.getType("L" + owner + ";");
      final Type[] methodParameterTypesWithThis = new Type[method.getArgumentTypes().length + 1];
      methodParameterTypesWithThis[0] = ownerType;
      System.arraycopy(
          method.getArgumentTypes(),
          0,
          methodParameterTypesWithThis,
          1,
          methodParameterTypesWithThis.length - 1);
      return methodParameterTypesWithThis;
    }

    protected boolean applyInvokeAdvice(
        final CallSiteAdvice advice, final String methodName, final String methodDescriptor) {
      if (!(advice instanceof InvokeAdvice)) {
        return false;
      }
      if (!"<init>".equals(methodName)) {
        return true;
      }
      // TODO: do not ignore ctors where there is no DUP after NEW
      return newFollowedByDup;
    }

    // Keep track of the latest opcode to match a DUP with its previous NEW
    @Override
    public void visitIntInsn(final int opcode, final int operand) {
      lastOpcode = opcode;
      super.visitIntInsn(opcode, operand);
    }

    @Override
    public void visitVarInsn(final int opcode, final int var) {
      lastOpcode = opcode;
      super.visitVarInsn(opcode, var);
    }

    @Override
    public void visitFieldInsn(
        final int opcode, final String owner, final String name, final String descriptor) {
      lastOpcode = opcode;
      super.visitFieldInsn(opcode, owner, name, descriptor);
    }

    @Override
    public void visitJumpInsn(final int opcode, final Label label) {
      lastOpcode = opcode;
      super.visitJumpInsn(opcode, label);
    }

    @Override
    public void visitLdcInsn(final Object value) {
      lastOpcode = Opcodes.LDC;
      super.visitLdcInsn(value);
    }

    @Override
    public void visitIincInsn(final int var, final int increment) {
      lastOpcode = Opcodes.IINC;
      super.visitIincInsn(var, increment);
    }

    @Override
    public void visitTableSwitchInsn(
        final int min, final int max, final Label dflt, final Label... labels) {
      lastOpcode = Opcodes.TABLESWITCH;
      super.visitTableSwitchInsn(min, max, dflt, labels);
    }

    @Override
    public void visitLookupSwitchInsn(final Label dflt, final int[] keys, final Label[] labels) {
      lastOpcode = Opcodes.LOOKUPSWITCH;
      super.visitLookupSwitchInsn(dflt, keys, labels);
    }

    @Override
    public void visitMultiANewArrayInsn(final String descriptor, final int numDimensions) {
      lastOpcode = Opcodes.MULTIANEWARRAY;
      super.visitMultiANewArrayInsn(descriptor, numDimensions);
    }
  }

  private static class CallSiteCtorMethodVisitor extends CallSiteMethodVisitor {

    private final Deque<String> newInvocations = new LinkedList<>();
    private boolean isSuperCall = false;

    private CallSiteCtorMethodVisitor(
        @Nonnull final Advices advices, @Nonnull final MethodVisitor delegated) {
      super(advices, delegated);
    }

    @Override
    public void visitEnd() {
      super.visitEnd();
      if (!newInvocations.isEmpty()) {
        LOGGER.debug(
            SEND_TELEMETRY,
            "There is an issue handling NEW bytecodes, remaining types {}",
            newInvocations);
      }
    }

    @Override
    public void visitTypeInsn(final int opcode, final String type) {
      if (opcode == Opcodes.NEW) {
        newInvocations.addLast(type);
      }
      super.visitTypeInsn(opcode, type);
    }

    @Override
    public void visitMethodInsn(
        final int opcode,
        final String owner,
        final String name,
        final String descriptor,
        final boolean isInterface) {
      try {
        if (opcode == Opcodes.INVOKESPECIAL && "<init>".equals(name)) {
          if (owner.equals(newInvocations.peekLast())) {
            newInvocations.removeLast();
            isSuperCall = false;
          } else {
            // no new before call to <init>
            isSuperCall = true;
          }
        }
        super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
      } finally {
        isSuperCall = false;
      }
    }

    @Override
    public void advice(final String owner, final String name, final String descriptor) {
      if (isSuperCall) {
        mv.visitIntInsn(Opcodes.ALOAD, 0); // append this to the stack after super call
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, owner, name, descriptor, false);
        mv.visitInsn(Opcodes.POP); // pop the result of the advice call
      } else {
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, owner, name, descriptor, false);
      }
    }

    @Override
    public void dupParameters(final String methodDescriptor, final StackDupMode mode) {
      super.dupParameters(
          methodDescriptor, isSuperCall ? StackDupMode.PREPEND_ARRAY_SUPER_CTOR : mode);
    }

    @Override
    protected void invokeAdvice(
        final InvokeAdvice advice,
        final int opcode,
        final String owner,
        final String name,
        final String descriptor,
        final boolean isInterface) {
      if (isSuperCall && advices.typeOf(advice) != AFTER) {
        // TODO APPSEC-57009 calls to super are only instrumented by after call sites
        // just ignore the advice and keep on
        mv.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
      } else {
        super.invokeAdvice(advice, opcode, owner, name, descriptor, isInterface);
      }
    }

    @Override
    protected boolean applyInvokeAdvice(
        final CallSiteAdvice advice, final String methodName, final String descriptor) {
      if (isSuperCall) {
        return advice instanceof InvokeAdvice && "<init>".equals(methodName);
      }
      return super.applyInvokeAdvice(advice, methodName, descriptor);
    }
  }
}
