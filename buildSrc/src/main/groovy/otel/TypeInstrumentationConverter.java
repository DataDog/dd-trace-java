package otel;

import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.tree.ClassNode;

/**
 * This class visitor converts OpenTelemetry TypeInstrumentation into Datadog instrumenter.
 */
public class TypeInstrumentationConverter extends AbstractClassVisitor {
  static final String TYPE_INSTRUMENTATION_CLASS_NAME = "io/opentelemetry/javaagent/extension/instrumentation/TypeInstrumentation";
  private static final String CLASS_CONSTRUCTOR_DESC = "()V";

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
    // TODO WIP

  }
}


