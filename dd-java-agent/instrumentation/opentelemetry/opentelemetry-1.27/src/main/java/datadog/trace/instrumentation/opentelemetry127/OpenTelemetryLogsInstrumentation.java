package datadog.trace.instrumentation.opentelemetry127;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.implementsInterface;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.returns;
import static net.bytebuddy.matcher.ElementMatchers.takesNoArguments;

import com.google.auto.service.AutoService;
import datadog.opentelemetry.shim.logs.OtelLoggerProvider;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.api.InstrumenterConfig;
import io.opentelemetry.api.logs.LoggerProvider;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

/**
 * Provides our logs implementations to OpenTelemetry clients.
 *
 * <p>Note that the minimum version for Datadog support of the OpenTelemetry logs API is 1.27.
 * Tracing support is handled by a separate instrumentation under the 'opentelemetry-1.4' module.
 */
@AutoService(InstrumenterModule.class)
public class OpenTelemetryLogsInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.CanShortcutTypeMatching, Instrumenter.HasMethodAdvice {

  public OpenTelemetryLogsInstrumentation() {
    super("opentelemetry-logs", "opentelemetry-1.27", "opentelemetry-1");
  }

  @Override
  protected boolean defaultEnabled() {
    return InstrumenterConfig.get().isLogsOtelEnabled();
  }

  @Override
  public String hierarchyMarkerType() {
    return "io.opentelemetry.api.OpenTelemetry";
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return named(hierarchyMarkerType()).or(implementsInterface(named(hierarchyMarkerType())));
  }

  @Override
  public String[] knownMatchingTypes() {
    return new String[] {
      "io.opentelemetry.api.DefaultOpenTelemetry",
      "io.opentelemetry.api.GlobalOpenTelemetry$ObfuscatedOpenTelemetry"
    };
  }

  @Override
  public boolean onlyMatchKnownTypes() {
    return isShortcutMatchingEnabled(false);
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      "datadog.opentelemetry.shim.logs.OtelLogger",
      "datadog.opentelemetry.shim.logs.OtelLoggerBuilder",
      "datadog.opentelemetry.shim.logs.OtelLoggerProvider",
      "datadog.opentelemetry.shim.logs.OtelLogRecordBuilder",
    };
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    // LoggerProvider OpenTelemetry.getLogsBridge()
    transformer.applyAdvice(
        isMethod()
            .and(named("getLogsBridge"))
            .and(takesNoArguments())
            .and(returns(named("io.opentelemetry.api.logs.LoggerProvider"))),
        OpenTelemetryLogsInstrumentation.class.getName() + "$LoggerProviderAdvice");
  }

  public static class LoggerProviderAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void returnProvider(@Advice.Return(readOnly = false) LoggerProvider result) {
      result = OtelLoggerProvider.INSTANCE;
    }
  }
}
