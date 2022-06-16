package datadog.trace.agent.tooling.bytebuddy.outline;

import java.lang.annotation.Annotation;
import java.lang.annotation.ElementType;
import java.lang.annotation.RetentionPolicy;
import java.util.Set;
import net.bytebuddy.description.annotation.AnnotationDescription;
import net.bytebuddy.description.annotation.AnnotationValue;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;

/** Annotation outline that just describes the type-name. */
final class AnnotationOutline extends WithName implements AnnotationDescription {

  AnnotationOutline(String descriptor) {
    super(descriptor.substring(1, descriptor.length() - 1).replace('/', '.'));
  }

  @Override
  public TypeDescription getAnnotationType() {
    return this;
  }

  @Override
  protected TypeDescription delegate() {
    return unavailable();
  }

  @Override
  public AnnotationValue<?, ?> getValue(String property) {
    return unavailable();
  }

  @Override
  public AnnotationValue<?, ?> getValue(MethodDescription.InDefinedShape property) {
    return unavailable();
  }

  @Override
  public <T extends Annotation> Loadable<T> prepare(Class<T> annotationType) {
    return unavailable();
  }

  @Override
  public RetentionPolicy getRetention() {
    return unavailable();
  }

  @Override
  public Set<ElementType> getElementTypes() {
    return unavailable();
  }

  @Override
  public boolean isSupportedOn(ElementType elementType) {
    return unavailable();
  }

  @Override
  public boolean isSupportedOn(String elementType) {
    return unavailable();
  }

  @Override
  public boolean isInherited() {
    return unavailable();
  }

  @Override
  public boolean isDocumented() {
    return unavailable();
  }

  private <T> T unavailable() {
    throw new IllegalStateException("Not available in outline for " + name);
  }
}
