package datadog.trace.instrumentation.log4j1;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static java.util.Collections.singletonMap;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.api.Config;
import datadog.trace.api.CorrelationIdentifier;
import datadog.trace.api.DDSpanId;
import datadog.trace.api.DDTraceId;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.api.AgentSpanContext;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import java.util.Hashtable;
import java.util.Map;
import net.bytebuddy.asm.Advice;
import org.apache.log4j.spi.LoggingEvent;

@AutoService(InstrumenterModule.class)
public class LoggingEventInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {
  public LoggingEventInstrumentation() {
    super("log4j", "log4j-1");
  }

  @Override
  public String instrumentedType() {
    return "org.apache.log4j.spi.LoggingEvent";
  }

  @Override
  public Map<String, String> contextStore() {
    return singletonMap("org.apache.log4j.spi.LoggingEvent", AgentSpanContext.class.getName());
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod().and(named("getMDC")).and(takesArgument(0, String.class)),
        LoggingEventInstrumentation.class.getName() + "$GetMdcAdvice");

    transformer.applyAdvice(
        isMethod().and(named("getMDCCopy")).and(takesArguments(0)),
        LoggingEventInstrumentation.class.getName() + "$GetMdcCopyAdvice");
  }

  public static class GetMdcAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void getMdcValue(
        @Advice.This LoggingEvent event,
        @Advice.Argument(0) String key,
        @Advice.Return(readOnly = false) Object value) {

      // The mdc has priority over our tags
      // if the mdc had a value for the key, or the key is null (invalid for a switch)
      // just return
      if (value != null || key == null) {
        return;
      }

      AgentSpanContext context =
          InstrumentationContext.get(LoggingEvent.class, AgentSpanContext.class).get(event);

      // Nothing to add so return early
      if (context == null && !AgentTracer.traceConfig().isLogsInjectionEnabled()) {
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
          if (context != null) {
            DDTraceId traceId = context.getTraceId();
            if (traceId.toHighOrderLong() != 0 && Config.get().isLogs128bitTraceIdEnabled()) {
              value = traceId.toHexString();
            } else {
              value = traceId.toString();
            }
          }
          break;
        case "dd.span_id":
          if (context != null) {
            value = DDSpanId.toString(context.getSpanId());
          }
          break;
        default:
      }
    }
  }

  public static class GetMdcCopyAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static boolean onEnter(
        @Advice.This LoggingEvent event,
        @Advice.FieldValue(value = "mdcCopyLookupRequired", readOnly = false)
            boolean copyRequired) {
      if (!copyRequired) {
        return false;
      }
      AgentSpanContext context =
          InstrumentationContext.get(LoggingEvent.class, AgentSpanContext.class).get(event);

      return (context != null || AgentTracer.traceConfig().isLogsInjectionEnabled());
    }

    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void onExit(
        @Advice.This LoggingEvent event,
        @Advice.Enter() boolean injectionRequired,
        @Advice.FieldValue(value = "mdcCopy") Hashtable mdc) {
      if (!injectionRequired) {
        return;
      }
      if (mdc == null) {
        // this.mdcCopy can be null when MDC.getContext() returns null
        return;
      }
      // at this point the mdc has been shallow copied. No need to replace with a new hashtable.
      // Just add our info
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

      AgentSpanContext context =
          InstrumentationContext.get(LoggingEvent.class, AgentSpanContext.class).get(event);

      if (context != null) {
        DDTraceId traceId = context.getTraceId();
        String traceIdValue =
            Config.get().isLogs128bitTraceIdEnabled() && traceId.toHighOrderLong() != 0
                ? traceId.toHexString()
                : traceId.toString();
        mdc.put(CorrelationIdentifier.getTraceIdKey(), traceIdValue);
        mdc.put(CorrelationIdentifier.getSpanIdKey(), DDSpanId.toString(context.getSpanId()));
      }
    }
  }
}
