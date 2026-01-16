package datadog.trace.agent.tooling.bytebuddy.outline;

import static datadog.trace.agent.tooling.bytebuddy.outline.AnnotationOutline.annotationOutline;
import static datadog.trace.agent.tooling.bytebuddy.outline.TypeFactory.findType;

import datadog.instrument.classmatch.ClassOutline;
import java.util.ArrayList;
import java.util.List;
import net.bytebuddy.description.annotation.AnnotationDescription;
import net.bytebuddy.description.annotation.AnnotationList;
import net.bytebuddy.description.field.FieldDescription;
import net.bytebuddy.description.field.FieldList;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.method.MethodList;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.description.type.TypeList;
import net.bytebuddy.jar.asm.Opcodes;

/** Provides an outline of a type; i.e. the named elements making up its structure. */
final class TypeOutline extends WithName {
  private static final int ALLOWED_TYPE_MODIFIERS = 0x0000ffdf; // excludes ACC_SUPER

  static final TypeList.Generic NO_TYPES = new TypeList.Generic.Empty();
  static final AnnotationList NO_ANNOTATIONS = new AnnotationList.Empty();

  private static final FieldList<FieldDescription.InDefinedShape> NO_FIELDS =
      new FieldList.Empty<>();
  private static final MethodList<MethodDescription.InDefinedShape> NO_METHODS =
      new MethodList.Empty<>();

  private final int modifiers;
  private final String superName;
  private final String[] interfaces;

  private List<AnnotationDescription> declaredAnnotations;

  private final List<FieldDescription.InDefinedShape> declaredFields = new ArrayList<>();
  private final List<MethodDescription.InDefinedShape> declaredMethods = new ArrayList<>();

  TypeOutline(int access, String internalName, String superName, String[] interfaces) {
    super(internalName.replace('/', '.'));
    this.modifiers = access & ALLOWED_TYPE_MODIFIERS;
    this.superName = superName;
    this.interfaces = interfaces;
  }

  /** Adapts simpler {@link ClassOutline} structure to existing {@link TypeOutline}. */
  public TypeOutline(ClassOutline outline) {
    super(outline.className.replace('/', '.'));
    this.modifiers = outline.access & ALLOWED_TYPE_MODIFIERS;
    this.superName = outline.superName;
    this.interfaces = outline.interfaces;

    for (String annotation : outline.annotations) {
      declare(annotationOutline(annotation));
    }

    for (datadog.instrument.classmatch.FieldOutline f : outline.fields) {
      declare(new FieldOutline(this, f.access, f.fieldName, f.descriptor));
    }

    for (datadog.instrument.classmatch.MethodOutline m : outline.methods) {
      MethodOutline method = new MethodOutline(this, m.access, m.methodName, m.descriptor);
      for (String annotation : m.annotations) {
        method.declare(annotationOutline(annotation));
      }
      declare(method);
    }
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
  public int getModifiers() {
    return modifiers;
  }

  @Override
  public boolean isAbstract() {
    return matchesMask(Opcodes.ACC_ABSTRACT);
  }

  @Override
  public boolean isEnum() {
    return matchesMask(Opcodes.ACC_ENUM);
  }

  @Override
  public boolean isInterface() {
    return matchesMask(Opcodes.ACC_INTERFACE);
  }

  @Override
  public boolean isAnnotation() {
    return matchesMask(Opcodes.ACC_ANNOTATION);
  }

  private boolean matchesMask(int mask) {
    return (this.getModifiers() & mask) == mask;
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
