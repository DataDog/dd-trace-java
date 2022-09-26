package datadog.trace.instrumentation.logback;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.implementsInterface;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static java.util.Collections.singletonMap;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import ch.qos.logback.classic.spi.ILoggingEvent;
import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.log.UnionMap;
import datadog.trace.api.Config;
import datadog.trace.api.CorrelationIdentifier;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import java.util.HashMap;
import java.util.Map;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.implementation.bytecode.assign.Assigner;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(Instrumenter.class)
public class LoggingEventInstrumentation extends Instrumenter.Tracing
    implements Instrumenter.ForTypeHierarchy {
  public LoggingEventInstrumentation() {
    super("logback");
  }

  @Override
  protected boolean defaultEnabled() {
    return Config.get().isLogsInjectionEnabled();
  }

  @Override
  public String hierarchyMarkerType() {
    return "ch.qos.logback.classic.spi.ILoggingEvent";
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return implementsInterface(named(hierarchyMarkerType()));
  }

  @Override
  public Map<String, String> contextStore() {
    return singletonMap(
        "ch.qos.logback.classic.spi.ILoggingEvent", AgentSpan.Context.class.getName());
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        isMethod().and(named("getMDCPropertyMap").or(named("getMdc"))).and(takesArguments(0)),
        LoggingEventInstrumentation.class.getName() + "$GetMdcAdvice");
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      "datadog.trace.agent.tooling.log.UnionMap",
      "datadog.trace.agent.tooling.log.UnionMap$1",
      "datadog.trace.agent.tooling.log.UnionMap$1$1",
    };
  }

  public static class GetMdcAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void onExit(
        @Advice.This ILoggingEvent event,
        @Advice.Return(typing = Assigner.Typing.DYNAMIC, readOnly = false)
            Map<String, String> mdc) {

      if (mdc instanceof UnionMap) {
        return;
      }

      AgentSpan.Context context =
          InstrumentationContext.get(ILoggingEvent.class, AgentSpan.Context.class).get(event);
      boolean mdcTagsInjectionEnabled = Config.get().isLogsMDCTagsInjectionEnabled();

      // Nothing to add so return early
      if (context == null && !mdcTagsInjectionEnabled) {
        return;
      }

      Map<String, String> correlationValues = new HashMap<>(8);

      if (context != null) {
        correlationValues.put(
            CorrelationIdentifier.getTraceIdKey(), context.getTraceId().toString());
        correlationValues.put(CorrelationIdentifier.getSpanIdKey(), context.getSpanId().toString());
      }

      if (mdcTagsInjectionEnabled) {
        String serviceName = Config.get().getServiceName();
        if (null != serviceName && !serviceName.isEmpty()) {
          correlationValues.put(Tags.DD_SERVICE, serviceName);
        }
        String env = Config.get().getEnv();
        if (null != env && !env.isEmpty()) {
          correlationValues.put(Tags.DD_ENV, env);
        }
        String version = Config.get().getVersion();
        if (null != version && !version.isEmpty()) {
          correlationValues.put(Tags.DD_VERSION, version);
        }
      }

      mdc = null != mdc ? new UnionMap<>(mdc, correlationValues) : correlationValues;
    }
  }
}
