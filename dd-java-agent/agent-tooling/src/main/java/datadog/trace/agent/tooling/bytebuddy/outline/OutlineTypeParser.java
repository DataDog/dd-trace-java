package datadog.trace.agent.tooling.bytebuddy.outline;

import static datadog.trace.agent.tooling.bytebuddy.outline.AnnotationOutline.annotationOutline;
import static net.bytebuddy.jar.asm.ClassReader.SKIP_CODE;
import static net.bytebuddy.jar.asm.ClassReader.SKIP_DEBUG;

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import net.bytebuddy.ClassFileVersion;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.jar.asm.AnnotationVisitor;
import net.bytebuddy.jar.asm.ClassReader;
import net.bytebuddy.jar.asm.ClassVisitor;
import net.bytebuddy.jar.asm.FieldVisitor;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Type;
import net.bytebuddy.utility.OpenedClassReader;

/** Attempts a minimal parse of just the named elements we need for matching. */
final class OutlineTypeParser implements TypeParser {

  @Override
  public TypeDescription parse(byte[] bytecode) {
    ClassReader classReader = OpenedClassReader.of(bytecode);
    OutlineTypeExtractor typeExtractor = new OutlineTypeExtractor();
    classReader.accept(typeExtractor, SKIP_CODE | SKIP_DEBUG);
    return typeExtractor.typeOutline;
  }

  @Override
  public TypeDescription parse(Class<?> loadedType) {
    Class<?> superClass = loadedType.getSuperclass();

    TypeOutline typeOutline =
        new TypeOutline(
            ClassFileVersion.ofThisVm().getMinorMajorVersion(),
            loadedType.getModifiers(),
            loadedType.getName(),
            null != superClass ? superClass.getName() : null,
            extractTypeNames(loadedType.getInterfaces()));

    for (Annotation a : loadedType.getDeclaredAnnotations()) {
      typeOutline.declare(annotationOutline(Type.getDescriptor(a.annotationType())));
    }

    for (Field field : loadedType.getDeclaredFields()) {
      FieldOutline fieldOutline =
          new FieldOutline(
              typeOutline,
              field.getModifiers(),
              field.getName(),
              Type.getDescriptor(field.getType()));
      for (Annotation a : field.getDeclaredAnnotations()) {
        fieldOutline.declare(annotationOutline(Type.getDescriptor(a.annotationType())));
      }
      typeOutline.declare(fieldOutline);
    }

    for (Method method : loadedType.getDeclaredMethods()) {
      MethodOutline methodOutline =
          new MethodOutline(
              typeOutline,
              method.getModifiers(),
              method.getName(),
              Type.getMethodDescriptor(method));
      for (Annotation a : method.getDeclaredAnnotations()) {
        methodOutline.declare(annotationOutline(Type.getDescriptor(a.annotationType())));
      }
      typeOutline.declare(methodOutline);
    }

    return typeOutline;
  }

  private static String[] extractTypeNames(Class[] types) {
    String[] typeNames = new String[types.length];
    for (int i = 0; i < types.length; i++) {
      typeNames[i] = types[i].getName();
    }
    return typeNames;
  }

  static final class OutlineTypeExtractor extends ClassVisitor {

    TypeOutline typeOutline;
    FieldOutline fieldOutline;
    MethodOutline methodOutline;

    OutlineTypeExtractor() {
      super(OpenedClassReader.ASM_API);
    }

    @Override
    public void visit(
        int version,
        int access,
        String name,
        String signature,
        String superName,
        String[] interfaces) {
      typeOutline = new TypeOutline(version, access, name, superName, interfaces);
    }

    @Override
    public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
      typeOutline.declare(annotationOutline(descriptor));
      return null;
    }

    @Override
    public FieldVisitor visitField(
        int access, String name, String descriptor, String signature, Object value) {
      fieldOutline = new FieldOutline(typeOutline, access, name, descriptor);
      typeOutline.declare(fieldOutline);
      return fieldAnnotationExtractor;
    }

    @Override
    public MethodVisitor visitMethod(
        int access, String name, String descriptor, String signature, String[] exceptions) {
      methodOutline = new MethodOutline(typeOutline, access, name, descriptor);
      typeOutline.declare(methodOutline);
      return methodAnnotationExtractor;
    }

    private final FieldVisitor fieldAnnotationExtractor =
        new FieldVisitor(OpenedClassReader.ASM_API) {
          @Override
          public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
            fieldOutline.declare(annotationOutline(descriptor));
            return null;
          }
        };

    private final MethodVisitor methodAnnotationExtractor =
        new MethodVisitor(OpenedClassReader.ASM_API) {
          @Override
          public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
            methodOutline.declare(annotationOutline(descriptor));
            return null;
          }
        };
  }
}
