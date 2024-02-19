package otel

import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.FieldVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Type
import org.slf4j.LoggerFactory

import static org.objectweb.asm.Opcodes.ASM9

/** This visitor checks no dependency on OTel agent left .*/
class OtelApiVerifier extends ClassVisitor {
  private static final LOGGER = LoggerFactory.getLogger(OtelApiVerifier.class)
  /** The visited class name. */
  private final String className

  protected OtelApiVerifier(ClassVisitor classVisitor, String className) {
    super(ASM9, classVisitor)
    this.className = className
  }

  // TODO: Only a POC. Need to cover more cases

  @Override
  FieldVisitor visitField(int access, String name, String descriptor, String signature, Object value) {
    checkType(Type.getType(descriptor), name)
    return super.visitField(access, name, descriptor, signature, value)
  }

  @Override
  MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
    Type.getArgumentTypes(descriptor).each {
      checkType(it, "parameter of $name")
    }
    checkType(Type.getReturnType(descriptor), "return of $name")
    return super.visitMethod(access, name, descriptor, signature, exceptions)
  }

  private void checkType(Type type, String name) {
    String typeClassName = type.getClassName()
    if (!isValidClass(typeClassName)) {
      LOGGER.warn("Invalid OpenTelemetry type found: found $name of type $typeClassName in class $className")
//      throw new IllegalStateException("Invalid OpenTelemetry type found: found $name of type $typeClassName in class $className")
    }
  }

  private static boolean isValidClass(String className) {
    return !className.startsWith("io.opentelemetry.javaagent.")
  }
}
