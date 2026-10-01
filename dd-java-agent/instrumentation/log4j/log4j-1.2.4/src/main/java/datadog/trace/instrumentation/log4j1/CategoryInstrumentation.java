/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package datadog.trace.instrumentation.log4j1;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.traceConfig;
import static java.util.Collections.singletonMap;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.api.InstrumenterConfig;
import datadog.trace.bootstrap.CallDepthThreadLocalMap;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentSpanContext;
import datadog.trace.bootstrap.instrumentation.log.AgentlessLogSubmission;
import java.util.Map;
import net.bytebuddy.asm.Advice;
import org.apache.log4j.spi.LoggingEvent;

@AutoService(InstrumenterModule.class)
public class CategoryInstrumentation extends InstrumenterModule.ContextTracking
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {
  public CategoryInstrumentation() {
    super("log4j", "log4j-1");
  }

  @Override
  public String instrumentedType() {
    return "org.apache.log4j.Category";
  }

  @Override
  public Map<String, String> contextStore() {
    return singletonMap("org.apache.log4j.spi.LoggingEvent", AgentSpanContext.class.getName());
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {packageName + ".LogsIntakeHelper"};
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod()
            .and(isPublic())
            .and(named("callAppenders"))
            .and(takesArguments(1))
            .and(takesArgument(0, named("org.apache.log4j.spi.LoggingEvent"))),
        CategoryInstrumentation.class.getName() + "$CallAppendersAdvice");
    if (InstrumenterConfig.get().isAgentlessLogSubmissionEnabled()) {
      transformer.applyAdvice(
          isMethod()
              .and(isPublic())
              .and(named("callAppenders"))
              .and(takesArguments(1))
              .and(takesArgument(0, named("org.apache.log4j.spi.LoggingEvent"))),
          CategoryInstrumentation.class.getName() + "$SubmitLogAdvice");
    }
  }

  public static class CallAppendersAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void onEnter(@Advice.Argument(0) LoggingEvent event) {
      AgentSpan span = activeSpan();

      if (span != null && traceConfig(span).isLogsInjectionEnabled()) {
        InstrumentationContext.get(LoggingEvent.class, AgentSpanContext.class)
            .put(event, span.spanContext());
      }
    }
  }

  public static class SubmitLogAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static boolean onEnter(@Advice.Argument(0) LoggingEvent event) {
      if (event == null
          || CallDepthThreadLocalMap.incrementCallDepth(AgentlessLogSubmission.class) > 0) {
        return false;
      }
      try {
        LogsIntakeHelper.submit(event);
      } catch (Throwable ignored) {
      }
      return true;
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void onExit(@Advice.Enter boolean shouldReset) {
      if (shouldReset) {
        CallDepthThreadLocalMap.reset(AgentlessLogSubmission.class);
      }
    }
  }
}
