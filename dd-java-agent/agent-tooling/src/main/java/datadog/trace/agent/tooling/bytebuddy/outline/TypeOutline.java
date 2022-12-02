package datadog.trace.agent.tooling.bytebuddy.outline;

import static datadog.trace.agent.tooling.bytebuddy.outline.TypeFactory.findType;

import java.util.ArrayList;
import java.util.List;
import net.bytebuddy.ClassFileVersion;
import net.bytebuddy.description.annotation.AnnotationDescription;
import net.bytebuddy.description.annotation.AnnotationList;
import net.bytebuddy.description.field.FieldDescription;
import net.bytebuddy.description.field.FieldList;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.method.MethodList;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.description.type.TypeList;

/** Provides an outline of a type; i.e. the named elements making up its structure. */
final class TypeOutline extends WithName {
  private static final int ALLOWED_TYPE_MODIFIERS = 0x0000ffdf; // excludes ACC_SUPER

  static final TypeList.Generic NO_TYPES = new TypeList.Generic.Empty();
  static final AnnotationList NO_ANNOTATIONS = new AnnotationList.Empty();

  private static final FieldList<FieldDescription.InDefinedShape> NO_FIELDS =
      new FieldList.Empty<>();
  private static final MethodList<MethodDescription.InDefinedShape> NO_METHODS =
      new MethodList.Empty<>();

  private final int classFileVersion;
  private final int modifiers;
  private final String superName;
  private final String[] interfaces;
  private String declaringName;

  private List<AnnotationDescription> declaredAnnotations;

  private final List<FieldDescription.InDefinedShape> declaredFields = new ArrayList<>();
  private final List<MethodDescription.InDefinedShape> declaredMethods = new ArrayList<>();

  TypeOutline(int version, int access, String internalName, String superName, String[] interfaces) {
    super(internalName.replace('/', '.'));
    this.classFileVersion = version;
    this.modifiers = access & ALLOWED_TYPE_MODIFIERS;
    this.superName = superName;
    this.interfaces = interfaces;
  }

  @Override
  protected TypeDescription delegate() {
    throw new IllegalStateException("Not available in outline for " + name);
  }

  @Override
  public Generic getSuperClass() {
    if (null != superName) {
      return findType(superName.replace('/', '.')).asGenericType();
    }
    return null;
  }

  @Override
  public TypeList.Generic getInterfaces() {
    if (interfaces.length == 0) {
      return NO_TYPES;
    }
    List<Generic> outlines = new ArrayList<>(interfaces.length);
    for (final String iface : interfaces) {
      outlines.add(findType(iface.replace('/', '.')).asGenericType());
    }
    return new TypeList.Generic.Explicit(outlines);
  }

  @Override
  public TypeDescription getDeclaringType() {
    if (null != declaringName) {
      return findType(declaringName.replace('/', '.'));
    }
    return null;
  }

  @Override
  public TypeDescription getEnclosingType() {
    return getDeclaringType(); // equivalent for outline purposes
  }

  @Override
  public int getModifiers() {
    return modifiers;
  }

  @Override
  public ClassFileVersion getClassFileVersion() {
    return ClassFileVersion.ofMinorMajor(classFileVersion);
  }

  @Override
  public AnnotationList getDeclaredAnnotations() {
    return null == declaredAnnotations
        ? NO_ANNOTATIONS
        : new AnnotationList.Explicit(declaredAnnotations);
  }

  @Override
  public FieldList<FieldDescription.InDefinedShape> getDeclaredFields() {
    return declaredFields.isEmpty() ? NO_FIELDS : new FieldList.Explicit<>(declaredFields);
  }

  @Override
  public MethodList<MethodDescription.InDefinedShape> getDeclaredMethods() {
    return declaredMethods.isEmpty() ? NO_METHODS : new MethodList.Explicit<>(declaredMethods);
  }

  void declaredBy(String declaringName) {
    this.declaringName = declaringName;
  }

  void declare(AnnotationDescription annotation) {
    if (null != annotation) {
      if (null == declaredAnnotations) {
        declaredAnnotations = new ArrayList<>();
      }
      declaredAnnotations.add(annotation);
    }
  }

  void declare(FieldDescription.InDefinedShape field) {
    if (null != field) {
      declaredFields.add(field);
    }
  }

  void declare(MethodDescription.InDefinedShape method) {
    if (null != method) {
      declaredMethods.add(method);
    }
  }
}
