package datadog.trace.agent.tooling.bytebuddy.outline;

import static datadog.trace.agent.tooling.bytebuddy.outline.TypeFactory.findDescriptor;

import java.util.ArrayList;
import java.util.List;
import net.bytebuddy.description.annotation.AnnotationDescription;
import net.bytebuddy.description.annotation.AnnotationList;
import net.bytebuddy.description.annotation.AnnotationValue;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.method.ParameterDescription;
import net.bytebuddy.description.method.ParameterList;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.description.type.TypeList;

/** Provides an outline of a method; its name, descriptor, and modifiers. */
final class MethodOutline extends MethodDescription.InDefinedShape.AbstractBase {
  private static final int ALLOWED_METHOD_MODIFIERS = 0x0000ffff;

  private final TypeDescription declaringType;

  private final String descriptor;
  private final int modifiers;
  private final String name;

  private final List<AnnotationDescription> declaredAnnotations = new ArrayList<>();

  MethodOutline(TypeDescription declaringType, int access, String name, String descriptor) {
    this.declaringType = declaringType;
    this.modifiers = access & ALLOWED_METHOD_MODIFIERS;
    this.name = name;
    this.descriptor = descriptor;
  }

  @Override
  public TypeDescription getDeclaringType() {
    return declaringType;
  }

  @Override
  public String getDescriptor() {
    return descriptor;
  }

  @Override
  public ParameterList<ParameterDescription.InDefinedShape> getParameters() {
    List<ParameterDescription.InDefinedShape> outlines = new ArrayList<>();
    int parameterCount = 0;
    int parameterStart = 1;
    while (descriptor.charAt(parameterStart) != ')') {
      int parameterEnd = parameterStart;
      while (descriptor.charAt(parameterEnd) == '[') {
        parameterEnd++;
      }
      if (descriptor.charAt(parameterEnd) == 'L') {
        parameterEnd = Math.max(parameterEnd, descriptor.indexOf(';', parameterEnd));
      }
      outlines.add(new ParameterOutline(parameterCount++, parameterStart, ++parameterEnd));
      parameterStart = parameterEnd;
    }
    return new ParameterList.Explicit<>(outlines);
  }

  @Override
  public TypeDescription.Generic getReturnType() {
    return findDescriptor(descriptor.substring(descriptor.lastIndexOf(')') + 1)).asGenericType();
  }

  @Override
  public AnnotationValue<?, ?> getDefaultValue() {
    return AnnotationValue.UNDEFINED;
  }

  @Override
  public String getInternalName() {
    return name;
  }

  @Override
  public TypeList.Generic getTypeVariables() {
    return new TypeList.Generic.Empty();
  }

  @Override
  public int getModifiers() {
    return modifiers;
  }

  @Override
  public AnnotationList getDeclaredAnnotations() {
    return new AnnotationList.Explicit(declaredAnnotations);
  }

  @Override
  public TypeList.Generic getExceptionTypes() {
    return new TypeList.Generic.Empty();
  }

  void declare(AnnotationDescription annotation) {
    if (null != annotation) {
      declaredAnnotations.add(annotation);
    }
  }

  class ParameterOutline extends ParameterDescription.InDefinedShape.AbstractBase {
    private final int index;
    private final int parameterStart;
    private final int parameterEnd;

    ParameterOutline(int index, int parameterStart, int parameterEnd) {
      this.index = index;
      this.parameterStart = parameterStart;
      this.parameterEnd = parameterEnd;
    }

    @Override
    public int getIndex() {
      return index;
    }

    @Override
    public TypeDescription.Generic getType() {
      return findDescriptor(descriptor.substring(parameterStart, parameterEnd)).asGenericType();
    }

    @Override
    public MethodDescription.InDefinedShape getDeclaringMethod() {
      return MethodOutline.this;
    }

    @Override
    public boolean hasModifiers() {
      return false;
    }

    @Override
    public boolean isNamed() {
      return false;
    }

    @Override
    public AnnotationList getDeclaredAnnotations() {
      return new AnnotationList.Empty();
    }
  }
}
