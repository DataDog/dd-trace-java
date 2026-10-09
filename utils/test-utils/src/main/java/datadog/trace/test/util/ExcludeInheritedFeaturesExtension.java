package datadog.trace.test.util;

import org.spockframework.runtime.extension.IAnnotationDrivenExtension;
import org.spockframework.runtime.model.FeatureInfo;
import org.spockframework.runtime.model.SpecInfo;

/** Handles specs annotated with {@link ExcludeInheritedFeatures}. */
public class ExcludeInheritedFeaturesExtension
    implements IAnnotationDrivenExtension<ExcludeInheritedFeatures> {

  @Override
  public void visitSpecAnnotation(final ExcludeInheritedFeatures annotation, final SpecInfo spec) {
    final SpecInfo superSpec = spec.getSuperSpec();
    if (superSpec == null) {
      return;
    }
    for (final FeatureInfo feature : superSpec.getAllFeatures()) {
      feature.setExcluded(true);
    }
  }
}
