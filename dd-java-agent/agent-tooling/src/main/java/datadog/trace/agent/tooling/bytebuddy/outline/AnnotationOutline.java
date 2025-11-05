package datadog.trace.agent.tooling.bytebuddy.outline;

import datadog.instrument.classmatch.ClassFile;
import java.lang.annotation.Annotation;
import java.lang.annotation.ElementType;
import java.lang.annotation.RetentionPolicy;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import net.bytebuddy.description.annotation.AnnotationDescription;
import net.bytebuddy.description.annotation.AnnotationValue;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;

/** Annotation outline that just describes the type-name. */
final class AnnotationOutline extends WithName implements AnnotationDescription {

  private static final Map<String, AnnotationDescription> annotationOutlines = new HashMap<>();

  static void prepareAnnotationOutline(String name) {
    String internalName = name.replace('.', '/');
    // flag that we want to parse this annotation
    ClassFile.annotationOfInterest(internalName);
    // only a few annotation outlines get prepared - we register them under
    // both their internal name and descriptor to support different callers
    AnnotationOutline annotationOutline = new AnnotationOutline(name);
    annotationOutlines.put('L' + internalName + ';', annotationOutline);
    annotationOutlines.put(internalName, annotationOutline);
  }

  /** Only provide outlines of annotations of interest used for matching. */
  static AnnotationDescription annotationOutline(String internalNameOrDescriptor) {
    return annotationOutlines.get(internalNameOrDescriptor);
  }

  private AnnotationOutline(String name) {
    super(name);
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
