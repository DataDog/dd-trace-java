package datadog.trace.instrumentation.java.lang.invoke;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.api.Platform;
import datadog.trace.bootstrap.instrumentation.java.lang.invoke.LambdaTransformerHelper;
import net.bytebuddy.asm.AsmVisitorWrapper;
import net.bytebuddy.description.field.FieldDescription;
import net.bytebuddy.description.field.FieldList;
import net.bytebuddy.description.method.MethodList;
import net.bytebuddy.description.type.TypeDefinition;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.implementation.Implementation;
import net.bytebuddy.jar.asm.ClassVisitor;
import net.bytebuddy.jar.asm.ClassWriter;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;
import net.bytebuddy.jar.asm.Type;
import net.bytebuddy.matcher.ElementMatcher;
import net.bytebuddy.pool.TypePool;
import net.bytebuddy.utility.OpenedClassReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Routes generated lambda bytes through the agent transformer before definition, allowing
 * instrumentations registered for an exact functional interface to transform them.
 *
 * <p>An ASM visitor is required because the transform call must be inserted immediately after the
 * lambda bytes are generated, in the middle of the metafactory method.
 *
 * <p>The injected call executes inside {@code java.lang.invoke}, so it only requires a module read
 * edge to the bootstrap helper; the package does not need to be opened reflectively.
 */
@AutoService(InstrumenterModule.class)
public final class LambdaMetafactoryInstrumentation extends InstrumenterModule.ContextTracking
    implements Instrumenter.ForBootstrap,
        Instrumenter.ForSingleType,
        Instrumenter.HasTypeAdvice,
        Instrumenter.WithTypeStructure {

  private static final Logger log = LoggerFactory.getLogger(LambdaMetafactoryInstrumentation.class);

  private static final String METAFACTORY = "java.lang.invoke.InnerClassLambdaMetafactory";

  private static final String LAMBDA_CLASS_NAME_FIELD = "lambdaClassName";
  private static final String TARGET_CLASS_FIELD = "targetClass";
  private static final String INTERFACE_CLASS_FIELD = "interfaceClass";
  private static final String LEGACY_INTERFACE_CLASS_FIELD = "samBase";

  public LambdaMetafactoryInstrumentation() {
    super("lambda");
  }

  @Override
  protected boolean defaultEnabled() {
    return false;
  }

  @Override
  public boolean isEnabled() {
    return super.isEnabled() && !Platform.isNativeImageBuilder();
  }

  @Override
  public String instrumentedType() {
    return METAFACTORY;
  }

  /** Require every field read by the injected bytecode. */
  @Override
  public ElementMatcher<TypeDescription> structureMatcher() {
    return HasMetafactoryFields.INSTANCE;
  }

  /** Public because this matcher is loaded across agent class-loader boundaries. */
  public static final class HasMetafactoryFields implements ElementMatcher<TypeDescription> {
    public static final HasMetafactoryFields INSTANCE = new HasMetafactoryFields();

    @Override
    public boolean matches(TypeDescription target) {
      return declaresField(target, LAMBDA_CLASS_NAME_FIELD, String.class.getName())
          && declaresField(target, TARGET_CLASS_FIELD, Class.class.getName())
          && interfaceClassField(target) != null;
    }

    static String interfaceClassField(TypeDescription type) {
      // JDK 8 and 11 use samBase; newer JDKs use interfaceClass.
      if (declaresField(type, INTERFACE_CLASS_FIELD, Class.class.getName())) {
        return INTERFACE_CLASS_FIELD;
      }
      if (declaresField(type, LEGACY_INTERFACE_CLASS_FIELD, Class.class.getName())) {
        return LEGACY_INTERFACE_CLASS_FIELD;
      }
      return null;
    }

    private static boolean declaresField(TypeDescription type, String name, String fieldType) {
      for (TypeDefinition current = type; current != null; current = current.getSuperClass()) {
        for (FieldDescription field : current.asErasure().getDeclaredFields()) {
          if (name.equals(field.getName())
              && fieldType.equals(field.getType().asErasure().getName())) {
            return true;
          }
        }
      }
      return false;
    }
  }

  @Override
  public void typeAdvice(TypeTransformer transformer) {
    transformer.applyAdvice(new MetafactoryVisitorWrapper());
  }

  public static final class MetafactoryVisitorWrapper implements AsmVisitorWrapper {
    @Override
    public int mergeWriter(int flags) {
      return flags | ClassWriter.COMPUTE_MAXS;
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
      return new MetafactoryClassVisitor(
          classVisitor,
          instrumentedType.getInternalName(),
          HasMetafactoryFields.interfaceClassField(instrumentedType));
    }
  }

  private static final class MetafactoryClassVisitor extends ClassVisitor {
    private final String slashClassName;
    private final String interfaceClassField;
    private boolean injected;

    MetafactoryClassVisitor(ClassVisitor cv, String slashClassName, String interfaceClassField) {
      super(OpenedClassReader.ASM_API, cv);
      this.slashClassName = slashClassName;
      this.interfaceClassField = interfaceClassField;
    }

    @Override
    public MethodVisitor visitMethod(
        int access, String name, String descriptor, String signature, String[] exceptions) {
      MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
      // The byte-generation method changed in JDK 25.
      if (("spinInnerClass".equals(name) || "generateInnerClass".equals(name))
          && "()Ljava/lang/Class;".equals(descriptor)) {
        return new MetafactoryMethodVisitor(api, mv, slashClassName, interfaceClassField, this);
      }
      return mv;
    }

    @Override
    public void visitEnd() {
      super.visitEnd();
      if (!injected) {
        log.debug(
            "No injection site found in {}; lambda transformation is inactive.", slashClassName);
      }
    }
  }

  private static final class MetafactoryMethodVisitor extends MethodVisitor {
    private final String slashClassName;
    private final String interfaceClassField;
    private final MetafactoryClassVisitor declaringVisitor;

    MetafactoryMethodVisitor(
        int api,
        MethodVisitor mv,
        String slashClassName,
        String interfaceClassField,
        MetafactoryClassVisitor declaringVisitor) {
      super(api, mv);
      this.slashClassName = slashClassName;
      this.interfaceClassField = interfaceClassField;
      this.declaringVisitor = declaringVisitor;
    }

    @Override
    public void visitMethodInsn(
        int opcode, String owner, String name, String descriptor, boolean isInterface) {
      super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
      // Match repackaged JDK APIs while excluding unrelated byte-array producers. The generated
      // byte[] remains on the operand stack after the original call.
      if ((opcode == Opcodes.INVOKEVIRTUAL
              && "toByteArray".equals(name)
              && "()[B".equals(descriptor)
              && owner.endsWith("/ClassWriter"))
          || (opcode == Opcodes.INVOKEINTERFACE
              && "build".equals(name)
              && descriptor.endsWith(")[B")
              && owner.endsWith("/ClassFile"))) {
        // stack: ..., byte[]
        super.visitVarInsn(Opcodes.ALOAD, 0);
        super.visitFieldInsn(
            Opcodes.GETFIELD, slashClassName, LAMBDA_CLASS_NAME_FIELD, "Ljava/lang/String;");
        super.visitVarInsn(Opcodes.ALOAD, 0);
        // Resolves the defining class loader and module.
        super.visitFieldInsn(
            Opcodes.GETFIELD, slashClassName, TARGET_CLASS_FIELD, "Ljava/lang/Class;");
        super.visitVarInsn(Opcodes.ALOAD, 0);
        // Allows the helper to reject interfaces without a registered ForLambda instrumentation.
        super.visitFieldInsn(
            Opcodes.GETFIELD, slashClassName, interfaceClassField, "Ljava/lang/Class;");
        super.visitMethodInsn(
            Opcodes.INVOKESTATIC,
            Type.getInternalName(LambdaTransformerHelper.class),
            "transform",
            "([BLjava/lang/String;Ljava/lang/Class;Ljava/lang/Class;)[B",
            false);
        // stack: ..., transformed byte[]
        declaringVisitor.injected = true;
      }
    }
  }
}
