package datadog.trace.agent.tooling.bytebuddy.outline;

import net.bytebuddy.description.annotation.AnnotationList;
import net.bytebuddy.description.field.FieldDescription;
import net.bytebuddy.description.field.FieldList;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.method.MethodList;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.description.type.TypeList;

/** Type description that lazily caches expensive results. */
final class CachingType extends WithName {
  private final TypeDescription delegate;

  private Generic superClass;
  private TypeList.Generic interfaces;
  private AnnotationList annotations;
  private FieldList<FieldDescription.InDefinedShape> fields;
  private MethodList<MethodDescription.InDefinedShape> methods;

  CachingType(TypeDescription delegate) {
    super(delegate.getName());
    this.delegate = delegate;
  }

  @Override
  protected TypeDescription delegate() {
    return delegate;
  }

  @Override
  public Generic getSuperClass() {
    if (superClass == null) {
      superClass = delegate.getSuperClass();
    }
    return superClass;
  }

  @Override
  public TypeList.Generic getInterfaces() {
    if (interfaces == null) {
      interfaces = delegate.getInterfaces();
    }
    return interfaces;
  }

  @Override
  public AnnotationList getDeclaredAnnotations() {
    if (annotations == null) {
      annotations = delegate.getDeclaredAnnotations();
    }
    return annotations;
  }

  @Override
  public FieldList<FieldDescription.InDefinedShape> getDeclaredFields() {
    if (fields == null) {
      fields = delegate.getDeclaredFields();
    }
    return fields;
  }

  @Override
  public MethodList<MethodDescription.InDefinedShape> getDeclaredMethods() {
    if (methods == null) {
      methods = delegate.getDeclaredMethods();
    }
    return methods;
  }
}
