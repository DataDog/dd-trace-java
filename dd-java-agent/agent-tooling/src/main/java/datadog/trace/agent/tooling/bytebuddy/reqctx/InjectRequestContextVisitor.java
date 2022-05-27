package datadog.trace.agent.tooling.bytebuddy.reqctx;

import datadog.trace.advice.ActiveRequestContext;
import datadog.trace.advice.RequiresRequestContext;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.api.gateway.RequestContextSlot;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import java.util.ArrayList;
import java.util.List;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.annotation.AnnotationList;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.method.MethodList;
import net.bytebuddy.description.method.ParameterDescription;
import net.bytebuddy.description.method.ParameterList;
import net.bytebuddy.description.type.TypeDefinition;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.jar.asm.AnnotationVisitor;
import net.bytebuddy.jar.asm.ClassVisitor;
import net.bytebuddy.jar.asm.Label;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;
import net.bytebuddy.jar.asm.Type;
import net.bytebuddy.jar.asm.signature.SignatureReader;
import net.bytebuddy.jar.asm.signature.SignatureVisitor;
import net.bytebuddy.jar.asm.signature.SignatureWriter;
import net.bytebuddy.utility.OpenedClassReader;

/**
 * Visitor that transforms advice annotated with {@link RequiresRequestContext} in such a way that
 * the advice is skipped if there is no active request context. Additionally, assigns the active
 * request context into the advice parameter that is annotated with {@link ActiveRequestContext}.
 * This annotation is replaced with {@link Advice.Local}. If there is no such parameter in the
 * advice method, one is added so that the request context need not be fetched both in the enter and
 * exit advices.
 */
public class InjectRequestContextVisitor extends ClassVisitor {
  private static final String REQUEST_CONTEXT_DESCRIPTOR = Type.getDescriptor(RequestContext.class);

  private final List<MethodDescription> methods;
  private final AdviceContent adviceContent;
  private final RequestContextSlot slot;

  public static ClassVisitor createVisitor(
      ClassVisitor cv, MethodList<?> methods, RequestContextSlot slot) {
    List<MethodDescription> methodList = new ArrayList<>();
    boolean hasOnEnter = false, hasOnExit = false;
    for (MethodDescription method : methods) {
      AnnotationList annotations = method.getDeclaredAnnotations();
      if (annotations.isAnnotationPresent(Advice.OnMethodEnter.class)) {
        hasOnEnter = true;
        methodList.add(method);
      } else if (annotations.isAnnotationPresent(Advice.OnMethodExit.class)) {
        hasOnExit = true;
        methodList.add(method);
      }
    }

    if (!hasOnEnter && !hasOnExit) {
      return cv;
    }

    return new InjectRequestContextVisitor(cv, methodList, hasOnEnter, hasOnExit, slot);
  }

  public InjectRequestContextVisitor(
      ClassVisitor cv,
      List<MethodDescription> methodList,
      boolean hasOnEnter,
      boolean hasOnExit,
      RequestContextSlot slot) {
    super(OpenedClassReader.ASM_API, cv);
    if (hasOnEnter && hasOnExit) {
      this.adviceContent = AdviceContent.HAS_BOTH;
    } else if (hasOnEnter) {
      this.adviceContent = AdviceContent.HAS_ENTER;
    } else {
      this.adviceContent = AdviceContent.HAS_EXIT;
    }
    this.methods = methodList;
    this.slot = slot;
  }

  private MethodDescription getMethodDescription(String name, String descriptor) {
    for (MethodDescription method : this.methods) {
      if (method.getName().equals(name) && method.getDescriptor().equals(descriptor)) {
        return method;
      }
    }
    return null;
  }

  @Override
  public MethodVisitor visitMethod(
      int access, String name, String descriptor, String signature, String[] exceptions) {
    MethodDescription methodDescription = getMethodDescription(name, descriptor);
    if (methodDescription == null) {
      return super.visitMethod(access, name, descriptor, signature, exceptions);
    }
    // method is relevant

    // already has a parameter annotated with ActiveRequestContext?
    // otherwise, we need to add a parameter
    boolean hasActiveReqCtxParam = hasActiveReqCtxParam(methodDescription);
    String newDescriptor = descriptor;
    String newSignature = signature;
    if (!hasActiveReqCtxParam) {
      int posClosingBracket = descriptor.indexOf(')');
      newDescriptor =
          descriptor.substring(0, posClosingBracket)
              + REQUEST_CONTEXT_DESCRIPTOR
              + descriptor.substring(posClosingBracket);
      if (signature != null) {
        newSignature = newMethodSignature(signature);
      }
    }

    MethodVisitor mv = super.visitMethod(access, name, newDescriptor, newSignature, exceptions);
    if (!hasActiveReqCtxParam) {
      mv = new LocalVariablesSorter(Opcodes.ACC_STATIC, descriptor, mv);
    }
    return new AdviceMethodVisitor(this.api, methodDescription, this.adviceContent, mv, this.slot);
  }

  private String newMethodSignature(String originalSignature) {
    SignatureReader signatureReader = new SignatureReader(originalSignature);
    SignatureWriter signatureWriter =
        new SignatureWriter() {
          @Override
          public SignatureVisitor visitReturnType() {
            visitParameterType().visitClassType(Type.getInternalName(RequestContext.class));
            visitEnd();
            return super.visitReturnType();
          }
        };
    signatureReader.accept(signatureWriter);
    return signatureWriter.toString();
  }

  private boolean hasActiveReqCtxParam(MethodDescription methodDescription) {
    for (ParameterDescription parameter : methodDescription.getParameters()) {
      if (parameter.getDeclaredAnnotations().isAnnotationPresent(ActiveRequestContext.class)) {
        return true;
      }
    }
    return false;
  }
}

enum AdviceContent {
  HAS_ENTER,
  HAS_EXIT,
  HAS_BOTH,
}

class AdviceMethodVisitor extends MethodVisitor {
  private static final String ACTIVE_REQUEST_CONTEXT_DESCRIPTOR =
      net.bytebuddy.jar.asm.Type.getDescriptor(ActiveRequestContext.class);
  private static final String ADVICE_LOCAL_DESCRIPTOR =
      net.bytebuddy.jar.asm.Type.getDescriptor(Advice.Local.class);

  private final boolean isEnter;
  private final AdviceContent adviceContent;
  private final MethodDescription methodDescription;
  private final int reqCtxParamIdx;
  private final Label beginLabel = new Label();
  private final Label popBeforeEpilogue = new Label();
  private final Label epilogueLabel = new Label();
  private final boolean addReqCtxParam;
  private final List<ParameterLocalVariable> paramLocalVars = new ArrayList<>();
  private final RequestContextSlot slot;

  AdviceMethodVisitor(
      int api,
      MethodDescription methodDescription,
      AdviceContent adviceContent,
      MethodVisitor methodVisitor,
      RequestContextSlot slot) {
    super(api, methodVisitor);
    this.methodDescription = methodDescription;
    this.adviceContent = adviceContent;

    this.isEnter =
        methodDescription.getDeclaredAnnotations().isAnnotationPresent(Advice.OnMethodEnter.class);

    int reqCtxParamIdx = -1;
    this.addReqCtxParam = mv instanceof LocalVariablesSorter;
    if (addReqCtxParam) {
      // if we're adding a new parameter, we need to remap the slots for the
      // existing local variables (add 1). We rely on the LocalVariablesSorter for this
      reqCtxParamIdx = methodDescription.getParameters().size();
      ((LocalVariablesSorter) mv)
          .newLocal(net.bytebuddy.jar.asm.Type.getType(RequestContext.class));
    } else {
      // else we just need to get the index for the current annotated parameter
      for (ParameterDescription p : methodDescription.getParameters()) {
        if (p.getDeclaredAnnotations().isAnnotationPresent(ActiveRequestContext.class)) {
          reqCtxParamIdx = p.getIndex();
        }
      }
    }
    if (reqCtxParamIdx == -1) {
      throw new IllegalStateException();
    }
    this.reqCtxParamIdx = reqCtxParamIdx;
    this.slot = slot;
  }

  @Override
  public AnnotationVisitor visitParameterAnnotation(
      int parameter, String descriptor, boolean visible) {
    if (!descriptor.equals(ACTIVE_REQUEST_CONTEXT_DESCRIPTOR)) {
      return super.visitParameterAnnotation(parameter, descriptor, visible);
    }

    // transform @ActiveRequestContext into @Advice.Local
    annotateReqCtxParam(parameter);
    return null;
  }

  @Override
  public void visitAnnotableParameterCount(int parameterCount, boolean visible) {
    if (!visible) {
      super.visitAnnotableParameterCount(parameterCount, false);
    } else {
      super.visitAnnotableParameterCount(
          this.addReqCtxParam ? parameterCount + 1 : parameterCount, true);
    }
  }

  private void annotateReqCtxParam(int parameter) {
    AnnotationVisitor annotationVisitor =
        super.visitParameterAnnotation(parameter, ADVICE_LOCAL_DESCRIPTOR, true);
    annotationVisitor.visit("value", "reqCtx");
    annotationVisitor.visitEnd();
  }

  @Override
  public void visitCode() {
    if (this.addReqCtxParam) {
      suppressSorter(
          new Runnable() {
            @Override
            public void run() {
              AdviceMethodVisitor.this.annotateReqCtxParam(AdviceMethodVisitor.this.reqCtxParamIdx);
            }
          });
    }

    super.visitCode();
    super.visitLabel(this.beginLabel);
    if (shouldFetchReqContext()) {
      super.visitMethodInsn(
          Opcodes.INVOKESTATIC,
          Type.getInternalName(AgentTracer.class),
          "activeSpan",
          "()" + Type.getDescriptor(AgentSpan.class),
          false);
      super.visitInsn(Opcodes.DUP);
      super.visitJumpInsn(Opcodes.IFNULL, this.popBeforeEpilogue);
      super.visitMethodInsn(
          Opcodes.INVOKEINTERFACE,
          Type.getInternalName(AgentSpan.class),
          "getRequestContext",
          "()" + Type.getDescriptor(RequestContext.class),
          true);
      super.visitInsn(Opcodes.DUP);
      super.visitJumpInsn(Opcodes.IFNULL, this.popBeforeEpilogue);

      super.visitInsn(Opcodes.DUP);
      super.visitFieldInsn(
          Opcodes.GETSTATIC,
          Type.getInternalName(RequestContextSlot.class),
          this.slot.name(),
          Type.getDescriptor(RequestContextSlot.class));
      super.visitMethodInsn(
          Opcodes.INVOKEINTERFACE,
          Type.getInternalName(RequestContext.class),
          "getData",
          "(" + Type.getDescriptor(RequestContextSlot.class) + ")Ljava/lang/Object;",
          true);
      super.visitJumpInsn(Opcodes.IFNULL, this.popBeforeEpilogue);
      suppressSorter(
          new Runnable() {
            @Override
            public void run() {
              AdviceMethodVisitor.super.visitVarInsn(
                  Opcodes.ASTORE, AdviceMethodVisitor.this.reqCtxParamIdx);
            }
          });
    } else {
      suppressSorter(
          new Runnable() {
            @Override
            public void run() {
              AdviceMethodVisitor.super.visitVarInsn(
                  Opcodes.ALOAD, AdviceMethodVisitor.this.reqCtxParamIdx);
            }
          });
      super.visitJumpInsn(Opcodes.IFNULL, this.epilogueLabel);
    }
  }

  @Override
  public void visitLocalVariable(
      String name, String descriptor, String signature, Label start, Label end, int index) {
    if (index < this.methodDescription.getParameters().size()) {
      // is a slot for an original parameter
      // save it so we can add it at the end and extend the instructions covered
      // bytebuddy expects advice classes to have the parameter locals cover the full function
      paramLocalVars.add(new ParameterLocalVariable(name, descriptor, signature, index));
    } else {
      super.visitLocalVariable(name, descriptor, signature, start, end, index);
    }
  }

  @Override
  public void visitEnd() {
    if (shouldFetchReqContext()) {
      super.visitLabel(this.popBeforeEpilogue);
      suppressSorter(
          new Runnable() {
            @Override
            public void run() {
              buildParameterFrame(new Object[] {Type.getInternalName(Object.class)});
            }
          });
      super.visitInsn(Opcodes.POP);
    }

    super.visitLabel(this.epilogueLabel);

    TypeDescription.Generic returnType = methodDescription.getReturnType();
    // add a frame with nothing in the stack and only the parameters as locals
    suppressSorter(
        new Runnable() {
          @Override
          public void run() {
            buildParameterFrame(new Object[0]);
          }
        });

    addDefaultReturn(returnType);

    final Label endLabel = new Label();
    super.visitLabel(endLabel);

    for (ParameterLocalVariable p : this.paramLocalVars) {
      super.visitLocalVariable(
          p.name, p.descriptor, p.signature, this.beginLabel, endLabel, p.index);
    }
    if (this.addReqCtxParam) {
      suppressSorter(
          new Runnable() {
            @Override
            public void run() {
              AdviceMethodVisitor.super.visitLocalVariable(
                  "reqCtx",
                  Type.getType(RequestContext.class).getDescriptor(),
                  null,
                  AdviceMethodVisitor.this.beginLabel,
                  endLabel,
                  AdviceMethodVisitor.this.reqCtxParamIdx);
            }
          });
    }

    super.visitEnd();
  }

  @Override
  public void visitMaxs(int maxStack, int maxLocals) {
    int usedStack = shouldFetchReqContext() ? 2 : 1;
    super.visitMaxs(Math.max(usedStack, maxStack), maxLocals);
  }

  private void addDefaultReturn(TypeDescription.Generic returnType) {
    if (returnType.represents(void.class)) {
      super.visitInsn(Opcodes.RETURN);
    } else if (returnType.represents(int.class)
        || returnType.represents(boolean.class)
        || returnType.represents(byte.class)
        || returnType.represents(char.class)
        || returnType.represents(short.class)) {
      super.visitInsn(Opcodes.ICONST_0);
      super.visitInsn(Opcodes.IRETURN);
    } else if (returnType.represents(long.class)) {
      super.visitInsn(Opcodes.LCONST_0);
      super.visitInsn(Opcodes.LRETURN);
    } else if (returnType.represents(float.class)) {
      super.visitInsn(Opcodes.FCONST_0);
      super.visitInsn(Opcodes.FRETURN);
    } else if (returnType.represents(double.class)) {
      super.visitInsn(Opcodes.DCONST_0);
      super.visitInsn(Opcodes.DRETURN);
    } else {
      super.visitInsn(Opcodes.ACONST_NULL);
      super.visitInsn(Opcodes.ARETURN);
    }
  }

  private boolean shouldFetchReqContext() {
    // we need to fetch if we're in @OnMethodEnter, or we're in an @OnMethodExit
    // in an advice class without an @OnMethodEnter.
    return this.isEnter || this.adviceContent == AdviceContent.HAS_EXIT;
  }

  private void suppressSorter(Runnable r) {
    MethodVisitor origMv = this.mv;
    if (!(origMv instanceof LocalVariablesSorter)) {
      r.run();
      return;
    }

    this.mv = ((LocalVariablesSorter) origMv).getWrappedMv();
    try {
      r.run();
    } finally {
      this.mv = origMv;
    }
  }

  private void buildParameterFrame(Object[] stack) {
    ParameterList<?> parameters = this.methodDescription.getParameters();
    Object[] frame = new Object[parameters.size() + (this.addReqCtxParam ? 1 : 0)];
    for (int index = 0; index < parameters.size(); index++) {
      TypeDefinition typeDefinition = parameters.get(index).getType();
      if (typeDefinition.represents(boolean.class)
          || typeDefinition.represents(byte.class)
          || typeDefinition.represents(short.class)
          || typeDefinition.represents(char.class)
          || typeDefinition.represents(int.class)) {
        frame[index] = Opcodes.INTEGER;
      } else if (typeDefinition.represents(long.class)) {
        frame[index] = Opcodes.LONG;
      } else if (typeDefinition.represents(float.class)) {
        frame[index] = Opcodes.FLOAT;
      } else if (typeDefinition.represents(double.class)) {
        frame[index] = Opcodes.DOUBLE;
      } else {
        frame[index] = typeDefinition.asErasure().getInternalName();
      }
    }
    if (this.addReqCtxParam) {
      frame[parameters.size()] = RequestContext.class.getName().replace('.', '/');
    }
    super.visitFrame(Opcodes.F_NEW, frame.length, frame, stack.length, stack);
  }
}

class ParameterLocalVariable {
  final String name;
  final String descriptor;
  final String signature;
  final int index;

  ParameterLocalVariable(String name, String descriptor, String signature, int index) {
    this.name = name;
    this.descriptor = descriptor;
    this.signature = signature;
    this.index = index;
  }
}
