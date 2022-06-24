package datadog.trace.agent.tooling.bytebuddy.outline;

import static datadog.trace.agent.tooling.bytebuddy.outline.TypeFactory.findDescriptor;

import java.util.ArrayList;
import java.util.List;
import net.bytebuddy.description.annotation.AnnotationDescription;
import net.bytebuddy.description.annotation.AnnotationList;
import net.bytebuddy.description.field.FieldDescription;
import net.bytebuddy.description.type.TypeDescription;

/** Provides an outline of a field; its name, descriptor, and modifiers. */
final class FieldOutline extends FieldDescription.InDefinedShape.AbstractBase {
  private static final int ALLOWED_FIELD_MODIFIERS = 0x0000ffff;

  private final TypeDescription declaringType;

  private final int modifiers;
  private final String name;
  private final String descriptor;

  private final List<AnnotationDescription> declaredAnnotations = new ArrayList<>();

  FieldOutline(TypeDescription declaringType, int access, String name, String descriptor) {
    this.declaringType = declaringType;
    this.modifiers = access & ALLOWED_FIELD_MODIFIERS;
    this.name = name;
    this.descriptor = descriptor;
  }

  @Override
  public TypeDescription getDeclaringType() {
    return declaringType;
  }

  @Override
  public int getModifiers() {
    return modifiers;
  }

  @Override
  public String getName() {
    return name;
  }

  @Override
  public String getDescriptor() {
    return descriptor;
  }

  @Override
  public TypeDescription.Generic getType() {
    return findDescriptor(descriptor).asGenericType();
  }

  @Override
  public AnnotationList getDeclaredAnnotations() {
    return new AnnotationList.Explicit(declaredAnnotations);
  }

  void declare(AnnotationDescription annotation) {
    if (null != annotation) {
      declaredAnnotations.add(annotation);
    }
  }
}
