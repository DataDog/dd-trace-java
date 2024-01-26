package datadog.trace.instrumentation.jbosslogmanager;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.extendsClass;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static java.util.Collections.singletonMap;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.log.UnionMap;
import datadog.trace.api.Config;
import datadog.trace.api.CorrelationIdentifier;
import datadog.trace.api.DDSpanId;
import datadog.trace.api.DDTraceId;
import datadog.trace.api.InstrumenterConfig;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import java.util.HashMap;
import java.util.Map;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.implementation.bytecode.assign.Assigner;
import net.bytebuddy.matcher.ElementMatcher;
import org.jboss.logmanager.ExtLogRecord;

@AutoService(Instrumenter.class)
public class ExtLogRecordInstrumentation extends Instrumenter.Tracing
    implements Instrumenter.ForTypeHierarchy {
  public ExtLogRecordInstrumentation() {
    super("jboss-logmanager");
  }

  @Override
  public String hierarchyMarkerType() {
    return "org.jboss.logmanager.ExtLogRecord";
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return extendsClass(named(hierarchyMarkerType()));
  }

  @Override
  public Map<String, String> contextStore() {
    return singletonMap("org.jboss.logmanager.ExtLogRecord", AgentSpan.Context.class.getName());
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod().and(named("getMdc")).and(takesArgument(0, String.class)),
        ExtLogRecordInstrumentation.class.getName() + "$GetMdcAdvice");

    transformer.applyAdvice(
        isMethod().and(named("getMdcCopy")).and(takesArguments(0)),
        ExtLogRecordInstrumentation.class.getName() + "$GetMdcCopyAdvice");
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
    public static void getMdcValue(
        @Advice.This ExtLogRecord record,
        @Advice.Argument(0) String key,
        @Advice.Return(readOnly = false) String value) {

      // if the mdc had a value for the key, or the key is null (invalid for a switch)
      // just return
      if (value != null || key == null) {
        return;
      }

      AgentSpan.Context context =
          InstrumentationContext.get(ExtLogRecord.class, AgentSpan.Context.class).get(record);

      // Nothing to add so return early
      if (context == null && !AgentTracer.traceConfig().isLogsInjectionEnabled()) {
        return;
      }

      switch (key) {
        case Tags.DD_SERVICE:
          value = Config.get().getServiceName();
          if (null != value && value.isEmpty()) {
            value = null;
          }
          break;
        case Tags.DD_ENV:
          value = Config.get().getEnv();
          if (null != value && value.isEmpty()) {
            value = null;
          }
          break;
        case Tags.DD_VERSION:
          value = Config.get().getVersion();
          if (null != value && value.isEmpty()) {
            value = null;
          }
          break;
        case "dd.trace_id":
          if (context != null) {
            DDTraceId traceId = context.getTraceId();
            if (traceId.toHighOrderLong() != 0
                && InstrumenterConfig.get().isLogs128bTraceIdEnabled()) {
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
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void onExit(
        @Advice.This ExtLogRecord record,
        @Advice.Return(typing = Assigner.Typing.DYNAMIC, readOnly = false)
            Map<String, String> mdc) {

      if (mdc instanceof UnionMap) {
        return;
      }

      AgentSpan.Context context =
          InstrumentationContext.get(ExtLogRecord.class, AgentSpan.Context.class).get(record);

      // Nothing to add so return early
      if (context == null && !AgentTracer.traceConfig().isLogsInjectionEnabled()) {
        return;
      }

      Map<String, String> correlationValues = new HashMap<>(8);

      if (context != null) {
        DDTraceId traceId = context.getTraceId();
        String traceIdValue =
            InstrumenterConfig.get().isLogs128bTraceIdEnabled() && traceId.toHighOrderLong() != 0
                ? traceId.toHexString()
                : traceId.toString();
        correlationValues.put(CorrelationIdentifier.getTraceIdKey(), traceIdValue);
        correlationValues.put(
            CorrelationIdentifier.getSpanIdKey(), DDSpanId.toString(context.getSpanId()));
      }

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

      mdc = null != mdc ? new UnionMap<>(mdc, correlationValues) : correlationValues;
    }
  }
}
