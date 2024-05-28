package otel;

import static org.objectweb.asm.Opcodes.ASM9;

import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * Base class visitor to convert OpenTelemetry class into their Datadog equivalence using Tree API.
 */
public class AbstractClassVisitor extends ClassVisitor {
  protected final ClassVisitor next;
  protected final String className;

  public AbstractClassVisitor(ClassVisitor classVisitor, String className) {
    super(ASM9, new ClassNode());
    this.next = classVisitor;
    this.className = className;
  }

  protected MethodNode findMethodNode(String methodName, String methodDesc) {
    ClassNode classNode = (ClassNode) this.cv;
    return classNode.methods.stream()
        .filter(node -> methodName.equals(node.name) && methodDesc.equals(node.desc))
        .findAny()
        .orElseThrow(() -> new IllegalStateException(
            "Unable to find method " + methodName + " from " + this.className
        ));
  }
}
