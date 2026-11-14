package datadog.smoketest

import org.spockframework.runtime.extension.IAnnotationDrivenExtension
import org.spockframework.runtime.model.FeatureInfo

class RunLastExtension implements IAnnotationDrivenExtension<RunLast> {
  @Override
  void visitFeatureAnnotations(List<RunLast> annotations, FeatureInfo feature) {
    if (!annotations.isEmpty()) {
      feature.setExecutionOrder(Integer.MAX_VALUE)
    }
  }
}
