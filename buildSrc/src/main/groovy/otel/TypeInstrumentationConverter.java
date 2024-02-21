package otel;

import static org.objectweb.asm.Opcodes.ACC_PUBLIC;
import static org.objectweb.asm.Opcodes.ACONST_NULL;
import static org.objectweb.asm.Opcodes.ARETURN;
import static org.objectweb.asm.Opcodes.ASM9;

import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * This class visitor converts OpenTelemetry TypeInstrumentation into Datadog instrumenter.
 */
public class TypeInstrumentationConverter extends AbstractClassVisitor {
  static final String TYPE_INSTRUMENTATION_CLASS_NAME = "io/opentelemetry/javaagent/extension/instrumentation/TypeInstrumentation";
  private static final String FOR_TYPE_HIERARCHY_CLASS_NAME = "datadog/trace/agent/tooling/Instrumenter$ForTypeHierarchy";
  private static final String HAS_TYPE_ADVICE_CLASS_NAME = "datadog/trace/agent/tooling/Instrumenter$HasTypeAdvice";
  private static final String TYPE_MATCHER_METHOD_NAME = "typeMatcher";
  private static final String TYPE_MATCHER_DESC = "()Lnet/bytebuddy/matcher/ElementMatcher;";
  private static final String HIERARCHY_MATCHER_METHOD_NAME = "hierarchyMatcher";
  private static final String TRANSFORM_METHOD_NAME = "transform";
  private static final String TRANSFORM_DESC = "(Lio/opentelemetry/javaagent/extension/instrumentation/TypeTransformer;)V";
  private static final String TYPE_ADVICE_METHOD_NAME = "typeAdvice";

  public TypeInstrumentationConverter(ClassVisitor classVisitor, String className) {
    super(classVisitor, className);
  }

  @Override
  public void visitEnd() {
    if (inheritsTypeInstrumentation()) {
      convertTypeInstrumentation();
    }
    if (this.next != null) {
      ClassNode cn = (ClassNode) this.cv;
      cn.accept(this.next);
    }
  }

  private boolean inheritsTypeInstrumentation() {
    ClassNode classNode = (ClassNode) this.cv;
    return classNode.interfaces.stream().anyMatch(TYPE_INSTRUMENTATION_CLASS_NAME::equals);
  }

  private void convertTypeInstrumentation() {
    ClassNode classNode = (ClassNode) this.cv;
    // Remove OTel TypeInstrumentation interface
    classNode.interfaces.remove(TYPE_INSTRUMENTATION_CLASS_NAME);
    // Implement Instrumenter.ForTypeHierarchy
    classNode.interfaces.add(FOR_TYPE_HIERARCHY_CLASS_NAME);
    addHierarchyMarkerTypeMethod();
    convertTypeMatcherMethod();
    // Implement Instrumenter.HasTypeAdvice
    classNode.interfaces.add(HAS_TYPE_ADVICE_CLASS_NAME);
    convertTransformMethod();
  }

  private void addHierarchyMarkerTypeMethod() {
    ClassNode classNode = (ClassNode) this.cv;

    MethodNode methodNode = new MethodNode(
        ASM9,
        ACC_PUBLIC,
        "hierarchyMarkerType",
        "()Ljava/lang/String;",
        "()Ljava/lang/String;",
        null
    );
    InsnList instructions = methodNode.instructions;
    instructions.add(new InsnNode(ACONST_NULL));
    instructions.add(new InsnNode(ARETURN));

    classNode.methods.add(methodNode);
  }

  private void convertTypeMatcherMethod() {
    MethodNode methodNode = findMethodNode(TYPE_MATCHER_METHOD_NAME, TYPE_MATCHER_DESC);
    methodNode.name = HIERARCHY_MATCHER_METHOD_NAME;
  }

  private void convertTransformMethod() {
    MethodNode methodNode = findMethodNode(TRANSFORM_METHOD_NAME, TRANSFORM_DESC);
    methodNode.name = TYPE_ADVICE_METHOD_NAME;
  }
}


