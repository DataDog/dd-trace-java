package datadog.opentelemetry.tooling;

import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.matcher.ElementMatcher;

/** Replaces OpenTelemetry's {@code TypeTransformer} callback when mapping extensions. */
public interface OtelTransformer {

  void applyAdviceToMethod(
      ElementMatcher<? super MethodDescription> methodMatcher, String adviceClassName);

  void applyTransformer(AgentBuilder.Transformer transformer);
}
