package datadog.trace.agent.tooling.bytebuddy.outline;

import static net.bytebuddy.jar.asm.ClassReader.SKIP_CODE;
import static net.bytebuddy.jar.asm.ClassReader.SKIP_DEBUG;

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import net.bytebuddy.ClassFileVersion;
import net.bytebuddy.description.annotation.AnnotationDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.jar.asm.AnnotationVisitor;
import net.bytebuddy.jar.asm.ClassReader;
import net.bytebuddy.jar.asm.ClassVisitor;
import net.bytebuddy.jar.asm.FieldVisitor;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Type;
import net.bytebuddy.utility.OpenedClassReader;

final class OutlineTypeParser implements TypeParser {

  private static final Map<String, AnnotationDescription> annotationsForMatching = new HashMap<>();

  @Override
  public TypeDescription parse(byte[] bytecode) {
    ClassReader classReader = OpenedClassReader.of(bytecode);
    OutlineTypeExtractor typeExtractor = new OutlineTypeExtractor();
    classReader.accept(typeExtractor, SKIP_CODE | SKIP_DEBUG);
    return typeExtractor.typeOutline;
  }

  static void registerAnnotationForMatching(String name) {
    String descriptor = 'L' + name.replace('.', '/') + ';';
    annotationsForMatching.put(descriptor, new AnnotationOutline(descriptor));
  }

  static AnnotationDescription annotationForMatching(String descriptor) {
    return annotationsForMatching.get(descriptor);
  }

  @Override
  public TypeDescription parse(Class<?> loadedType) {
    TypeOutline typeOutline =
        new TypeOutline(
            ClassFileVersion.ofThisVm().getMinorMajorVersion(),
            loadedType.getModifiers(),
            loadedType.getName(),
            loadedType.getSuperclass().getName(),
            extractTypeNames(loadedType.getInterfaces()));

    for (Annotation a : loadedType.getDeclaredAnnotations()) {
      typeOutline.declare(annotationForMatching(Type.getDescriptor(a.annotationType())));
    }

    for (Field field : loadedType.getDeclaredFields()) {
      FieldOutline fieldOutline =
          new FieldOutline(
              typeOutline,
              field.getModifiers(),
              field.getName(),
              Type.getDescriptor(field.getType()));
      for (Annotation a : field.getDeclaredAnnotations()) {
        fieldOutline.declare(annotationForMatching(Type.getDescriptor(a.annotationType())));
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
        methodOutline.declare(annotationForMatching(Type.getDescriptor(a.annotationType())));
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

    private TypeOutline typeOutline;
    private FieldOutline fieldOutline;
    private MethodOutline methodOutline;

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
      typeOutline.declare(annotationForMatching(descriptor));
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
            fieldOutline.declare(annotationForMatching(descriptor));
            return null;
          }
        };

    private final MethodVisitor methodAnnotationExtractor =
        new MethodVisitor(OpenedClassReader.ASM_API) {
          @Override
          public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
            methodOutline.declare(annotationForMatching(descriptor));
            return null;
          }
        };
  }
}
