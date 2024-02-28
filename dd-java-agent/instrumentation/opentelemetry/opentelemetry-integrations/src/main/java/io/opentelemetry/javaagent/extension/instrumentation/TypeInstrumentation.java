package io.opentelemetry.javaagent.extension.instrumentation;

import static net.bytebuddy.matcher.ElementMatchers.any;

import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

/*
 * This interface is vendor from https://github.com/open-telemetry/opentelemetry-java-instrumentation.
 */
public interface TypeInstrumentation {
  default ElementMatcher<ClassLoader> classLoaderOptimization() {
    return any();
  }

  ElementMatcher<TypeDescription> typeMatcher();

  void transform(TypeTransformer transformer);
}
