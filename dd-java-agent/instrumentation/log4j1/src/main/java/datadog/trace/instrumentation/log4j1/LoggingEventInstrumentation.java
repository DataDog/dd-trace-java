package datadog.trace.instrumentation.log4j1;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static java.util.Collections.singletonMap;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.api.Config;
import datadog.trace.api.CorrelationIdentifier;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.Map;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import org.apache.log4j.MDC;
import org.apache.log4j.spi.LoggingEvent;

@AutoService(Instrumenter.class)
public class LoggingEventInstrumentation extends Instrumenter.Tracing {
  public LoggingEventInstrumentation() {
    super("log4j", "log4j-1");
  }

  @Override
  protected boolean defaultEnabled() {
    return Config.get().isLogsInjectionEnabled();
  }

  @Override
  public ElementMatcher<? super TypeDescription> typeMatcher() {
    return named("org.apache.log4j.spi.LoggingEvent");
  }

  @Override
  public Map<String, String> contextStore() {
    return singletonMap("org.apache.log4j.spi.LoggingEvent", AgentSpan.Context.class.getName());
  }

  @Override
  public Map<? extends ElementMatcher<? super MethodDescription>, String> transformers() {
    Map<ElementMatcher<MethodDescription>, String> transformers = new HashMap<>();
    transformers.put(
        isMethod().and(named("getMDC")).and(takesArgument(0, String.class)),
        LoggingEventInstrumentation.class.getName() + "$GetMdcAdvice");

    transformers.put(
        isMethod().and(named("getMDCCopy")).and(takesArguments(0)),
        LoggingEventInstrumentation.class.getName() + "$GetMdcCopyAdvice");

    return transformers;
  }

  public static class GetMdcAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void getMdcValue(
        @Advice.This LoggingEvent event,
        @Advice.Argument(0) String key,
        @Advice.Return(readOnly = false) Object value) {

      // The mdc has priority of our tags
      // if the mdc had a value for the key, or the key is null (invalid for a switch)
      // just return
      if (value != null || key == null) {
        return;
      }

      switch (key) {
        case Tags.DD_SERVICE:
          value = Config.get().getServiceName();
          if (null != value && ((String) value).isEmpty()) {
            value = null;
          }
          break;
        case Tags.DD_ENV:
          value = Config.get().getEnv();
          if (null != value && ((String) value).isEmpty()) {
            value = null;
          }
          break;
        case Tags.DD_VERSION:
          value = Config.get().getVersion();
          if (null != value && ((String) value).isEmpty()) {
            value = null;
          }
          break;
        case "dd.trace_id":
          {
            AgentSpan.Context context =
                InstrumentationContext.get(LoggingEvent.class, AgentSpan.Context.class).get(event);
            if (context != null) {
              value = context.getTraceId().toString();
            }
          }
          break;
        case "dd.span_id":
          {
            AgentSpan.Context context =
                InstrumentationContext.get(LoggingEvent.class, AgentSpan.Context.class).get(event);
            if (context != null) {
              value = context.getSpanId().toString();
            }
          }
          break;
        default:
      }
    }
  }

  public static class GetMdcCopyAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void onEnter(
        @Advice.This LoggingEvent event,
        @Advice.FieldValue(value = "mdcCopyLookupRequired", readOnly = false) boolean copyRequired,
        @Advice.FieldValue(value = "mdcCopy", readOnly = false) Hashtable mdcCopy) {
      // this advice basically replaces the original method
      // since copyRequired will be false when the original method is executed
      if (copyRequired) {
        copyRequired = false;

        Hashtable mdc = new Hashtable();

        if (Config.get().isLogsMDCTagsInjectionEnabled()) {
          String serviceName = Config.get().getServiceName();
          if (null != serviceName && !serviceName.isEmpty()) {
            mdc.put(Tags.DD_SERVICE, serviceName);
          }
          String env = Config.get().getEnv();
          if (null != env && !env.isEmpty()) {
            mdc.put(Tags.DD_ENV, env);
          }
          String version = Config.get().getVersion();
          if (null != version && !version.isEmpty()) {
            mdc.put(Tags.DD_VERSION, version);
          }
        }

        AgentSpan.Context context =
            InstrumentationContext.get(LoggingEvent.class, AgentSpan.Context.class).get(event);
        if (context != null) {
          mdc.put(CorrelationIdentifier.getTraceIdKey(), context.getTraceId().toString());
          mdc.put(CorrelationIdentifier.getSpanIdKey(), context.getTraceId().toString());
        }

        Hashtable originalMdc = MDC.getContext();
        if (originalMdc != null) {
          mdc.putAll(originalMdc);
        }

        mdcCopy = mdc;
      }
    }
  }
}
