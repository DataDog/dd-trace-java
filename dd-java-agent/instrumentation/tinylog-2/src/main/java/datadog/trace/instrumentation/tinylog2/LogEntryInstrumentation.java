package datadog.trace.instrumentation.tinylog2;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static java.util.Collections.singletonMap;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.agent.tooling.log.UnionMap;
import datadog.trace.api.Config;
import datadog.trace.api.CorrelationIdentifier;
import datadog.trace.api.DDSpanId;
import datadog.trace.api.DDTraceId;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.api.AgentSpanContext;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import java.util.HashMap;
import java.util.Map;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.implementation.bytecode.assign.Assigner;
import org.tinylog.core.LogEntry;

@AutoService(InstrumenterModule.class)
public class LogEntryInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {
  public LogEntryInstrumentation() {
    super("tinylog");
  }

  @Override
  public String instrumentedType() {
    return "org.tinylog.core.LogEntry";
  }

  @Override
  public Map<String, String> contextStore() {
    return singletonMap("org.tinylog.core.LogEntry", AgentSpanContext.class.getName());
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod().and(named("getContext")).and(takesArguments(0)),
        LogEntryInstrumentation.class.getName() + "$GetContextAdvice");
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      "datadog.trace.agent.tooling.log.UnionMap",
      "datadog.trace.agent.tooling.log.UnionMap$1",
      "datadog.trace.agent.tooling.log.UnionMap$1$1",
    };
  }

  public static class GetContextAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void onExit(
        @Advice.This LogEntry event,
        @Advice.Return(typing = Assigner.Typing.DYNAMIC, readOnly = false)
            Map<String, String> mdc) {

      if (mdc instanceof UnionMap) {
        return;
      }

      AgentSpanContext context =
          InstrumentationContext.get(LogEntry.class, AgentSpanContext.class).get(event);

      // TinyLoggingProviderInstrumentation only populates the context if injection is enabled
      // Impossible for context to be not null while injection is disabled
      if (context == null && !AgentTracer.traceConfig().isLogsInjectionEnabled()) {
        // Nothing to add so return early
        return;
      }

      Map<String, String> correlationValues = new HashMap<>(8);

      if (context != null) {
        DDTraceId traceId = context.getTraceId();
        String traceIdValue =
            Config.get().isLogs128bitTraceIdEnabled() && traceId.toHighOrderLong() != 0
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
