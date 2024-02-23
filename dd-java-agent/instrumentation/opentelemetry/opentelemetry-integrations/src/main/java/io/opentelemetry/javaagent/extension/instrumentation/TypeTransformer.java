package io.opentelemetry.javaagent.extension.instrumentation;

import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.matcher.ElementMatcher;

/*
 * This interface is vendor from https://github.com/open-telemetry/opentelemetry-java-instrumentation.
 */
public interface TypeTransformer {
  void applyAdviceToMethod(
      ElementMatcher<? super MethodDescription> methodMatcher, String adviceClassName);

  void applyTransformer(AgentBuilder.Transformer transformer);
}
