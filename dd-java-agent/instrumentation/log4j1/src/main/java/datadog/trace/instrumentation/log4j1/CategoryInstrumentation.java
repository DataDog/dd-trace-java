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
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.util.Map;
import net.bytebuddy.asm.Advice;
import org.apache.log4j.spi.LoggingEvent;

@AutoService(Instrumenter.class)
public class CategoryInstrumentation extends Instrumenter.Tracing
    implements Instrumenter.ForSingleType {
  public CategoryInstrumentation() {
    super("log4j", "log4j-1");
  }

  @Override
  public String instrumentedType() {
    return "org.apache.log4j.Category";
  }

  @Override
  public Map<String, String> contextStore() {
    return singletonMap("org.apache.log4j.spi.LoggingEvent", AgentSpan.Context.class.getName());
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        isMethod()
            .and(isPublic())
            .and(named("callAppenders"))
            .and(takesArguments(1))
            .and(takesArgument(0, named("org.apache.log4j.spi.LoggingEvent"))),
        CategoryInstrumentation.class.getName() + "$CallAppendersAdvice");
  }

  public static class CallAppendersAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void onEnter(@Advice.Argument(0) LoggingEvent event) {
      AgentSpan span = activeSpan();

      if (span != null && traceConfig(span).isLogsInjectionEnabled()) {
        InstrumentationContext.get(LoggingEvent.class, AgentSpan.Context.class)
            .put(event, span.context());
      }
    }
  }
}
