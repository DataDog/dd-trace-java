package datadog.trace.instrumentation.opentracing32;

import static java.util.Collections.singletonMap;
import static net.bytebuddy.matcher.ElementMatchers.isTypeInitializer;
import static net.bytebuddy.matcher.ElementMatchers.named;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.muzzle.ReferenceCreator;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import io.opentracing.util.GlobalTracer;
import java.util.Map;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

/**
 * OT interface is reimplemented here rather than using dd-trace-ot as a dependency to allow muzzle
 * to work correctly since it relies on the {@link ReferenceCreator#REFERENCE_CREATION_PACKAGE}
 * prefix.
 */
@AutoService(Instrumenter.class)
public class GlobalTracerInstrumentation extends Instrumenter.Default {
  public GlobalTracerInstrumentation() {
    super("opentracing", "opentracing-globaltracer");
  }

  @Override
  public ElementMatcher<TypeDescription> typeMatcher() {
    return named("io.opentracing.util.GlobalTracer");
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".OTTracer",
      packageName + ".OTTracer$OTSpanBuilder",
      packageName + ".OTPropagation$TextMapInjectSetter",
      packageName + ".OTPropagation$TextMapExtractGetter",
      packageName + ".OTScopeManager",
      packageName + ".OTScopeManager$OTScope",
      packageName + ".OTScopeManager$OTTraceScope",
      packageName + ".TypeConverter",
      packageName + ".OTSpan",
      packageName + ".OTSpanContext",
      "datadog.trace.instrumentation.opentracing.LogHandler",
      "datadog.trace.instrumentation.opentracing.DefaultLogHandler",
    };
  }

  @Override
  public Map<? extends ElementMatcher<? super MethodDescription>, String> transformers() {
    return singletonMap(
        isTypeInitializer(), GlobalTracerInstrumentation.class.getName() + "$GlobalTracerAdvice");
  }

  public static class GlobalTracerAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void registerTracer() {
      if (AgentTracer.isRegistered()) {
        GlobalTracer.registerIfAbsent(new OTTracer(AgentTracer.get()));
      }
    }
  }
}
