package datadog.trace.instrumentation.opentracing31;

import static datadog.trace.agent.tooling.ClassLoaderMatcher.hasClassesNamed;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isTypeInitializer;
import static net.bytebuddy.matcher.ElementMatchers.not;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.muzzle.ReferenceCreator;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import io.opentracing.util.GlobalTracer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

/**
 * OT interface is reimplemented here rather than using dd-trace-ot as a dependency to allow muzzle
 * to work correctly since it relies on the {@link ReferenceCreator#REFERENCE_CREATION_PACKAGE}
 * prefix.
 */
@AutoService(Instrumenter.class)
public final class GlobalTracerInstrumentation extends Instrumenter.Tracing {
  public GlobalTracerInstrumentation() {
    super("opentracing", "opentracing-globaltracer");
  }

  @Override
  public ElementMatcher<ClassLoader> classLoaderMatcher() {
    return not(hasClassesNamed("io.opentracing.tag.Tag"));
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
      packageName + ".OTTextMapSetter",
      packageName + ".OTScopeManager",
      packageName + ".OTScopeManager$OTScope",
      packageName + ".TypeConverter",
      packageName + ".OTSpan",
      packageName + ".OTSpanContext",
      "datadog.trace.instrumentation.opentracing.LogHandler",
      "datadog.trace.instrumentation.opentracing.DefaultLogHandler",
    };
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        isTypeInitializer(), GlobalTracerInstrumentation.class.getName() + "$GlobalTracerAdvice");
  }

  public static final class GlobalTracerAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void registerTracer() {
      if (AgentTracer.isRegistered()) {
        GlobalTracer.register(new OTTracer(AgentTracer.get()));
      }
    }
  }
}
