package datadog.trace.agent.tooling.bytebuddy;

import static datadog.trace.agent.tooling.bytebuddy.InvokeDynamicHelpersPlugin.InvokeDynamicHelpersClassVisitor.SYNTHETIC_LOG_METHOD;
import static net.bytebuddy.jar.asm.Opcodes.ACC_FINAL;
import static net.bytebuddy.jar.asm.Opcodes.ACC_PRIVATE;
import static net.bytebuddy.jar.asm.Opcodes.ACC_STATIC;
import static net.bytebuddy.jar.asm.Opcodes.ACONST_NULL;
import static net.bytebuddy.jar.asm.Opcodes.ALOAD;
import static net.bytebuddy.jar.asm.Opcodes.ARETURN;
import static net.bytebuddy.jar.asm.Opcodes.ATHROW;
import static net.bytebuddy.jar.asm.Opcodes.DCONST_0;
import static net.bytebuddy.jar.asm.Opcodes.DLOAD;
import static net.bytebuddy.jar.asm.Opcodes.DRETURN;
import static net.bytebuddy.jar.asm.Opcodes.DUP;
import static net.bytebuddy.jar.asm.Opcodes.FCONST_0;
import static net.bytebuddy.jar.asm.Opcodes.FLOAD;
import static net.bytebuddy.jar.asm.Opcodes.FRETURN;
import static net.bytebuddy.jar.asm.Opcodes.F_FULL;
import static net.bytebuddy.jar.asm.Opcodes.F_SAME1;
import static net.bytebuddy.jar.asm.Opcodes.GETSTATIC;
import static net.bytebuddy.jar.asm.Opcodes.ICONST_0;
import static net.bytebuddy.jar.asm.Opcodes.IFEQ;
import static net.bytebuddy.jar.asm.Opcodes.ILOAD;
import static net.bytebuddy.jar.asm.Opcodes.INSTANCEOF;
import static net.bytebuddy.jar.asm.Opcodes.INVOKEINTERFACE;
import static net.bytebuddy.jar.asm.Opcodes.INVOKESTATIC;
import static net.bytebuddy.jar.asm.Opcodes.INVOKEVIRTUAL;
import static net.bytebuddy.jar.asm.Opcodes.IRETURN;
import static net.bytebuddy.jar.asm.Opcodes.LCONST_0;
import static net.bytebuddy.jar.asm.Opcodes.LLOAD;
import static net.bytebuddy.jar.asm.Opcodes.LRETURN;
import static net.bytebuddy.jar.asm.Opcodes.PUTSTATIC;
import static net.bytebuddy.jar.asm.Opcodes.RETURN;
import static net.bytebuddy.jar.asm.Opcodes.SWAP;
import static net.bytebuddy.matcher.ElementMatchers.isSubTypeOf;

import datadog.trace.api.iast.InvokeDynamicHelper;
import datadog.trace.api.iast.InvokeDynamicHelperContainer;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.bytebuddy.asm.AsmVisitorWrapper;
import net.bytebuddy.build.Plugin;
import net.bytebuddy.description.annotation.AnnotationList;
import net.bytebuddy.description.field.FieldDescription;
import net.bytebuddy.description.field.FieldList;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.method.MethodList;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.implementation.Implementation;
import net.bytebuddy.jar.asm.ClassVisitor;
import net.bytebuddy.jar.asm.FieldVisitor;
import net.bytebuddy.jar.asm.Label;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;
import net.bytebuddy.jar.asm.Type;
import net.bytebuddy.pool.TypePool;
import net.bytebuddy.utility.OpenedClassReader;

/**
 * Transforms methods of classes implementing {@link InvokeDynamicHelperContainer} that are
 * annotated with {@link InvokeDynamicHelper} so that they are exception-safe.
 */
public class InvokeDynamicHelpersPlugin extends Plugin.ForElementMatcher {
  public InvokeDynamicHelpersPlugin(File targetDir) {
    super(isSubTypeOf(InvokeDynamicHelperContainer.class));
  }

  @Override
  public DynamicType.Builder<?> apply(
      final DynamicType.Builder<?> builder,
      final TypeDescription typeDescription,
      final ClassFileLocator classFileLocator) {
    return builder.visit(new InvokeDynamicHelpersVisitorWrapper());
  }

  @Override
  public void close() throws IOException {}

  public static class InvokeDynamicHelpersVisitorWrapper implements AsmVisitorWrapper {
    @Override
    public int mergeWriter(int flags) {
      return flags;
    }

    @Override
    public int mergeReader(int flags) {
      return flags;
    }

    @Override
    public ClassVisitor wrap(
        TypeDescription instrumentedType,
        ClassVisitor classVisitor,
        Implementation.Context implementationContext,
        TypePool typePool,
        FieldList<FieldDescription.InDefinedShape> fields,
        MethodList<?> methods,
        int writerFlags,
        int readerFlags) {
      Map<MethodDescription, String /* fallback method */> relevantMethods =
          annotatedMethods(methods);
      return new InvokeDynamicHelpersClassVisitor(classVisitor, relevantMethods);
    }

    private Map<MethodDescription, String> annotatedMethods(MethodList<?> allMethods) {
      Map<MethodDescription, String> methods = new HashMap<>();
      for (MethodDescription m : allMethods) {
        AnnotationList annotations = m.getDeclaredAnnotations();
        if (annotations.isAnnotationPresent(InvokeDynamicHelper.class)) {
          String fallbackMethod =
              annotations.ofType(InvokeDynamicHelper.class).load().fallbackMethod();
          methods.put(m, fallbackMethod);
        }
      }
      return methods;
    }
  }

  public static class InvokeDynamicHelpersClassVisitor extends net.bytebuddy.jar.asm.ClassVisitor {
    private final Map<MethodDescription, String> relevantMethods;
    private boolean visitedAnyMethod;
    private boolean foundExistingClinit;
    private String className;
    static final String SYNTHETIC_LOG_METHOD = "LOG$";

    protected InvokeDynamicHelpersClassVisitor(
        ClassVisitor classVisitor, Map<MethodDescription, String> relevantMethods) {
      super(OpenedClassReader.ASM_API, classVisitor);
      this.relevantMethods = relevantMethods;
    }

    @Override
    public void visit(
        int version,
        int access,
        String name,
        String signature,
        String superName,
        String[] interfaces) {
      super.visit(version, access, name, signature, superName, interfaces);
      this.className = name;
    }

    @Override
    public MethodVisitor visitMethod(
        int access, String name, String descriptor, String signature, String[] exceptions) {
      maybeAddField();

      if (name.equals("<clinit>")) {
        foundExistingClinit = true;
        MethodVisitor superMV = super.visitMethod(access, name, descriptor, signature, exceptions);
        return new ModifyClinitMethodVisitor(api, superMV, this.className);
      }

      if (!isRelevantMethod(name, descriptor)) {
        return super.visitMethod(access, name, descriptor, signature, exceptions);
      }

      String /* nullable */ fallbackMethod = fallbackMethod(name, descriptor);
      MethodVisitor superMV =
          super.visitMethod(
              access, name, descriptor, signature, new String[] {"java/lang/Throwable"});
      return new MakeExceptionSafeMethodVisitor(
          api, superMV, name, descriptor, fallbackMethod, this.className);
    }

    @Override
    public void visitEnd() {
      if (foundExistingClinit) {
        super.visitEnd();
        return;
      }

      MethodVisitor mv =
          new ModifyClinitMethodVisitor(
              api, super.visitMethod(ACC_STATIC, "<clinit>", "()V", null, null), this.className);
      mv.visitCode();
      mv.visitInsn(RETURN);
      mv.visitMaxs(0, 0);
      mv.visitEnd();

      super.visitEnd();
    }

    private boolean isRelevantMethod(String name, String descriptor) {
      for (MethodDescription m : this.relevantMethods.keySet()) {
        if (m.getName().equals(name) && m.getDescriptor().equals(descriptor)) {
          return true;
        }
      }
      return false;
    }

    private String fallbackMethod(String name, String descriptor) {
      for (Map.Entry<MethodDescription, String> e : this.relevantMethods.entrySet()) {
        if (e.getKey().getName().equals(name) && e.getKey().getDescriptor().equals(descriptor)) {
          return e.getValue().equals("") ? null : e.getValue();
        }
      }
      throw new IllegalStateException(); // should not happen
    }

    /** Adds the <code>LOG$</code> field. */
    private void maybeAddField() {
      if (visitedAnyMethod) {
        return;
      }
      visitedAnyMethod = true;

      FieldVisitor fv =
          super.visitField(
              ACC_PRIVATE | ACC_FINAL | ACC_STATIC,
              SYNTHETIC_LOG_METHOD,
              "Lorg/slf4j/Logger;",
              null,
              null);
      fv.visitEnd();
    }
  }

  /** Initializes the <code>LOG$</code> field. */
  private static class ModifyClinitMethodVisitor extends MethodVisitor {
    private final String className;

    public ModifyClinitMethodVisitor(int api, MethodVisitor superMV, String className) {
      super(api, superMV);
      this.className = className;
    }

    @Override
    public void visitCode() {
      super.visitCode();
      visitLdcInsn(Type.getType("L" + this.className + ";"));
      visitMethodInsn(
          INVOKESTATIC,
          "org/slf4j/LoggerFactory",
          "getLogger",
          "(Ljava/lang/Class;)Lorg/slf4j/Logger;",
          false);
      visitFieldInsn(PUTSTATIC, this.className, SYNTHETIC_LOG_METHOD, "Lorg/slf4j/Logger;");
    }

    @Override
    public void visitMaxs(int maxStack, int maxLocals) {
      super.visitMaxs(Math.max(1, maxStack), maxLocals);
    }
  }

  /**
   * Transforms the annotated methods by wrapping them in:
   *
   * <pre>
   *   try {
   *     return originalCode(args...);
   *   } catch (Throwable e) {
   *     if (e instanceof RealCallThrowable) {
   *       throw StackUtils.filterDatadog(e.getCause());
   *     }
   *     LOG$.warn("Helper XXX has thrown", e);
   *     return fallbackMethod(args...);
   *   }
   * </pre>
   *
   * or, if there is no fallback method:
   *
   * <pre>
   *   try {
   *     return originalCode(args...);
   *   } catch (Throwable e) {
   *     LOG$.warn("Helper XXX has thrown", e);
   *     return (default value for return type);
   *   }
   * </pre>
   */
  public static class MakeExceptionSafeMethodVisitor extends MethodVisitor {
    final String methodName;
    final Type[] argTypes;
    final Type retType;
    final String fallbackMethod;
    final String className;

    final Label tryStartLabel = new Label();
    final Label tryEndLabel = new Label();
    final Label catchLabel = new Label();
    final Label endFunctionLabel = new Label();

    final List<LocalVariableVisitation> lvVisitations = new ArrayList<>();

    protected MakeExceptionSafeMethodVisitor(
        int api,
        MethodVisitor methodVisitor,
        String methodName,
        String methodDescriptor,
        String fallbackMethod,
        String className) {
      super(api, methodVisitor);
      this.methodName = methodName;
      this.argTypes = Type.getArgumentTypes(methodDescriptor);
      this.retType = Type.getReturnType(methodDescriptor);
      this.fallbackMethod = fallbackMethod;
      this.className = className;
    }

    @Override
    public void visitCode() {
      super.visitCode();

      super.visitTryCatchBlock(tryStartLabel, tryEndLabel, catchLabel, "java/lang/Throwable");
      super.visitLabel(tryStartLabel);
    }

    private static final Pattern FALLBACK_REF_PATTERN =
        Pattern.compile("([^.]+)\\.([^.(]+)(\\([^.]+)", 0);

    @Override
    public void visitMaxs(int maxStack, int maxLocals) {
      super.visitLabel(tryEndLabel);
      super.visitLabel(catchLabel);
      super.visitFrame(
          F_FULL, numArguments(), argTypesForFrame(), 1, new Object[] {"java/lang/Throwable"});
      if (fallbackMethod != null) {
        super.visitInsn(DUP); // dup exception for consumption by instanceof
        super.visitTypeInsn(INSTANCEOF, "datadog/trace/api/iast/RealCallThrowable");
        Label notRealCallThrLabel = new Label();
        super.visitJumpInsn(IFEQ, notRealCallThrLabel);
        super.visitMethodInsn(
            INVOKEVIRTUAL, "java/lang/Throwable", "getCause", "()Ljava/lang/Throwable;", false);
        super.visitMethodInsn(
            INVOKESTATIC,
            "datadog/trace/util/stacktrace/StackUtils",
            "filterDatadog",
            "(Ljava/lang/Throwable;)Ljava/lang/Throwable;",
            false);
        super.visitInsn(ATHROW);
        super.visitLabel(notRealCallThrLabel);
        super.visitFrame(F_SAME1, 0, null, 1, new Object[] {"java/lang/Throwable"});
      }

      super.visitFieldInsn(GETSTATIC, className, SYNTHETIC_LOG_METHOD, "Lorg/slf4j/Logger;");
      super.visitInsn(SWAP); // Log, exc
      super.visitLdcInsn(
          "Helper " + className.replace('/', '.') + "#" + methodName + " has thrown");
      super.visitInsn(SWAP); // Log, string, exc
      super.visitMethodInsn(
          INVOKEINTERFACE,
          "org/slf4j/Logger",
          "warn",
          "(Ljava/lang/String;Ljava/lang/Throwable;)V",
          true);
      if (fallbackMethod != null) {
        Matcher m = FALLBACK_REF_PATTERN.matcher(fallbackMethod);
        if (!m.matches()) {
          System.err.println("Invalid fallback method: " + fallbackMethod);
          throw new RuntimeException("Invalid fallback method: " + fallbackMethod);
        }
        String owner = m.group(1);
        String method = m.group(2);
        String descriptor = m.group(3);
        loadAllArguments();
        super.visitMethodInsn(INVOKESTATIC, owner, method, descriptor, false);
        issueReturnIns();
      } else {
        issueDefaultReturn();
      }
      super.visitLabel(endFunctionLabel);

      for (LocalVariableVisitation lvVisitation : this.lvVisitations) {
        lvVisitation.visit(mv);
      }

      int numLocalArgsSlots = numLocalArgSlots();
      int ourMaxStack = Math.max(2, numLocalArgsSlots);
      super.visitMaxs(Math.max(maxStack, ourMaxStack), maxLocals);
    }

    @Override
    public void visitLocalVariable(
        String name, String descriptor, String signature, Label start, Label end, int index) {
      if (index < numLocalArgSlots()) {
        this.lvVisitations.add(
            new LocalVariableVisitation(
                name, descriptor, signature, start, endFunctionLabel, index));
      } else {
        super.visitLocalVariable(name, descriptor, signature, start, end, index);
      }
    }

    private void issueReturnIns() {
      if (retType.equals(Type.VOID_TYPE)) {
        super.visitInsn(RETURN);
      } else if (isRegularIntegerType(retType)) {
        super.visitInsn(IRETURN);
      } else if (retType.equals(Type.LONG_TYPE)) {
        super.visitInsn(LRETURN);
      } else if (retType.equals(Type.FLOAT_TYPE)) {
        super.visitInsn(FRETURN);
      } else if (retType.equals(Type.DOUBLE_TYPE)) {
        super.visitInsn(DRETURN);
      } else {
        super.visitInsn(ARETURN);
      }
    }

    private void issueDefaultReturn() {
      if (retType.equals(Type.VOID_TYPE)) {
        super.visitInsn(RETURN);
      } else if (isRegularIntegerType(retType)) {
        mv.visitInsn(ICONST_0);
        super.visitInsn(IRETURN);
      } else if (retType.equals(Type.LONG_TYPE)) {
        mv.visitInsn(LCONST_0);
        super.visitInsn(LRETURN);
      } else if (retType.equals(Type.FLOAT_TYPE)) {
        mv.visitInsn(FCONST_0);
        super.visitInsn(FRETURN);
      } else if (retType.equals(Type.DOUBLE_TYPE)) {
        mv.visitInsn(DCONST_0);
        super.visitInsn(DRETURN);
      } else {
        mv.visitInsn(ACONST_NULL);
        super.visitInsn(ARETURN);
      }
    }

    private Object[] argTypesForFrame() {
      Object[] ret = new Object[this.argTypes.length];
      for (int i = 0; i < this.argTypes.length; i++) {
        Type type = this.argTypes[i];
        if (isRegularIntegerType(type)) {
          ret[i] = Opcodes.INTEGER;
        } else if (type.equals(Type.FLOAT_TYPE)) {
          ret[i] = Opcodes.FLOAT;
        } else if (type.equals(Type.LONG_TYPE)) {
          ret[i] = Opcodes.LONG;
        } else if (type.equals(Type.DOUBLE_TYPE)) {
          ret[i] = Opcodes.DOUBLE;
        } else {
          ret[i] = type.getInternalName();
        }
      }
      return ret;
    }

    private void loadAllArguments() {
      int slot = 0;
      for (Type type : this.argTypes) {
        if (isRegularIntegerType(type)) {
          super.visitVarInsn(ILOAD, slot);
          slot++;
        } else if (type.equals(Type.FLOAT_TYPE)) {
          super.visitVarInsn(FLOAD, slot);
          slot++;
        } else if (type.equals(Type.LONG_TYPE)) {
          super.visitVarInsn(LLOAD, slot);
          slot += 2;
        } else if (type.equals(Type.DOUBLE_TYPE)) {
          super.visitVarInsn(DLOAD, slot);
          slot += 2;
        } else {
          super.visitVarInsn(ALOAD, slot);
          slot++;
        }
      }
    }

    private int numArguments() {
      return this.argTypes.length;
    }

    private int numLocalArgSlots() {
      int slots = 0;
      for (Type type : this.argTypes) {
        if (type.equals(Type.LONG_TYPE) || type.equals(Type.DOUBLE_TYPE)) {
          slots += 2;
        } else {
          slots++;
        }
      }
      return slots;
    }

    private boolean isRegularIntegerType(Type type) {
      return type.equals(Type.BOOLEAN_TYPE)
          || type.equals(Type.BYTE_TYPE)
          || type.equals(Type.CHAR_TYPE)
          || type.equals(Type.SHORT_TYPE)
          || type.equals(Type.INT_TYPE);
    }

    private static class LocalVariableVisitation {
      final String name;
      final String descriptor;
      final String signature;
      final Label start;
      final Label end;
      final int index;

      private LocalVariableVisitation(
          String name, String descriptor, String signature, Label start, Label end, int index) {
        this.name = name;
        this.descriptor = descriptor;
        this.signature = signature;
        this.start = start;
        this.end = end;
        this.index = index;
      }

      public void visit(MethodVisitor mv) {
        mv.visitLocalVariable(name, descriptor, signature, start, end, index);
      }
    }
  }
}
