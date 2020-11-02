package datadog.trace.instrumentation.opentelemetry.context;

import static datadog.trace.agent.tooling.bytebuddy.matcher.DDElementMatchers.implementsInterface;
import static java.util.Collections.singletonMap;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.returns;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import java.util.Map;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

/**
 * This is experimental instrumentation and should only be enabled for evaluation/testing purposes.
 */
@AutoService(Instrumenter.class)
public class OpenTelemetryContextInstrumentation extends Instrumenter.Default {
  public OpenTelemetryContextInstrumentation() {
    super("opentelemetry-beta");
  }

  @Override
  protected boolean defaultEnabled() {
    return false;
  }

  @Override
  public ElementMatcher<TypeDescription> typeMatcher() {
    return implementsInterface(named("io.opentelemetry.context.ContextStorage"));
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      "datadog.trace.instrumentation.opentelemetry.OtelSpan",
      "datadog.trace.instrumentation.opentelemetry.TypeConverter",
      "datadog.trace.instrumentation.opentelemetry.TypeConverter$OtelSpanContext",
      packageName + ".WrappedScope",
    };
  }

  @Override
  public Map<? extends ElementMatcher<? super MethodDescription>, String> transformers() {
    return singletonMap(
        named("attach")
            .and(takesArgument(0, named("io.opentelemetry.context.Context")))
            .and(returns(named("io.opentelemetry.context.Scope"))),
        packageName + ".ContextStorageAdvice");
  }
}
