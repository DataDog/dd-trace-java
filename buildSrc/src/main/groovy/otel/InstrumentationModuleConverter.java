package otel;

import static org.objectweb.asm.Opcodes.ANEWARRAY;
import static org.objectweb.asm.Opcodes.INVOKESPECIAL;
import static org.objectweb.asm.tree.AbstractInsnNode.METHOD_INSN;
import static org.objectweb.asm.tree.AbstractInsnNode.TYPE_INSN;
import static otel.TypeInstrumentationConverter.TYPE_INSTRUMENTATION_CLASS_NAME;

import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;

/**
 * This class visitor converts OpenTelemetry InstrumentationModule into Datadog InstrumenterModule (Tracing).
 */
public class InstrumentationModuleConverter extends AbstractClassVisitor {
  private static final String INSTRUMENTATION_MODULE_CLASS_NAME = "io/opentelemetry/javaagent/extension/instrumentation/InstrumentationModule";
  private static final String INSTRUMENTATION_MODULE_CONSTRUCTOR_DESC = "(Ljava/lang/String;[Ljava/lang/String;)V";
  private static final String OTEL_AUTOMATIC_INSTRUMENTATION_CLASS_NAME = "datadog/trace/instrumentation/automatic/OtelAutomaticInstrumentation";
  private static final String INSTRUMENTER_CLASS_NAME = "datadog/trace/agent/tooling/Instrumenter";
  private static final String CLASS_CONSTRUCTOR_DESC = "()V";
  private static final String TYPE_INSTRUMENTATIONS_METHOD_NAME = "typeInstrumentations";
  private static final String TYPE_INSTRUMENTATIONS_DESC = "()Ljava/util/List;";

  public InstrumentationModuleConverter(ClassVisitor classVisitor, String className) {
    super(classVisitor, className);
  }

  @Override
  public void visitEnd() {
    if (extendsInstrumentationModule()) {
      convertParentClass();
      convertTypeInstrumentations();
    }
    if (this.next != null) {
      ClassNode cn = (ClassNode) this.cv;
      cn.accept(this.next);
    }
  }

  private boolean extendsInstrumentationModule() {
    ClassNode classNode = (ClassNode) this.cv;
    return INSTRUMENTATION_MODULE_CLASS_NAME.equals(classNode.superName);
  }

  private void convertParentClass() {
    ClassNode classNode = (ClassNode) this.cv;
    // Update parent class
    classNode.superName = OTEL_AUTOMATIC_INSTRUMENTATION_CLASS_NAME;
    // Rewrite constructor to call the Datadog parent constructor
    MethodNode constructorMethodNode = findMethodNode("<init>", CLASS_CONSTRUCTOR_DESC);
    InsnList instructions = new InsnList();
    for (AbstractInsnNode instruction : constructorMethodNode.instructions) {
      if (instruction.getType() == METHOD_INSN) {
        MethodInsnNode methodInsnNode = (MethodInsnNode) instruction;
        if (methodInsnNode.getOpcode() == INVOKESPECIAL
            && INSTRUMENTATION_MODULE_CLASS_NAME.equals(methodInsnNode.owner)
            && INSTRUMENTATION_MODULE_CONSTRUCTOR_DESC.equals(methodInsnNode.desc)) {
          methodInsnNode.owner = OTEL_AUTOMATIC_INSTRUMENTATION_CLASS_NAME;
        }
      }
      instructions.add(instruction);
    }
    constructorMethodNode.instructions = instructions;
  }

  private void convertTypeInstrumentations() {
    // Rewrite typeInstrumentations() to create the Datadog type of array
    MethodNode methodNode = findMethodNode(TYPE_INSTRUMENTATIONS_METHOD_NAME, TYPE_INSTRUMENTATIONS_DESC);
    InsnList instructions = new InsnList();
    for (AbstractInsnNode instruction : methodNode.instructions) {
      if (instruction.getType() == TYPE_INSN) {
        TypeInsnNode typeInsnNode = (TypeInsnNode) instruction;
        if (typeInsnNode.getOpcode() == ANEWARRAY && TYPE_INSTRUMENTATION_CLASS_NAME.equals(typeInsnNode.desc)) {
          typeInsnNode.desc = INSTRUMENTER_CLASS_NAME;
        }
      }
      instructions.add(instruction);
    }
    methodNode.instructions = instructions;
  }
}
