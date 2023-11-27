/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package datadog.trace.instrumentation.tinylog;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.traceConfig;
import static java.util.Collections.singletonMap;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isPrivate;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.util.Map;
import net.bytebuddy.asm.Advice;
import org.tinylog.core.LogEntry;
import org.tinylog.core.TinylogLoggingProvider;

@AutoService(Instrumenter.class)
public class TinylogLoggingProviderInstrumentation extends Instrumenter.Tracing
    implements Instrumenter.ForSingleType {
  public TinylogLoggingProviderInstrumentation() {
    super("tinylog");
  }

  @Override
  public String instrumentedType() {
    return TinylogLoggingProvider.class.getName();
  }

  @Override
  public Map<String, String> contextStore() {
    return singletonMap(LogEntry.class.getName(), AgentSpan.Context.class.getName());
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        isMethod()
            .and(isPrivate())
            .and(named("output"))
            .and(takesArguments(2))
            .and(takesArgument(0, named("org.tinylog.core.LogEntry"))),
        TinylogLoggingProviderInstrumentation.class.getName() + "$OutputAdvice");
  }

  public static class OutputAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void onEnter(@Advice.Argument(0) LogEntry event) {
      AgentSpan span = activeSpan();

      if (span != null && traceConfig(span).isLogsInjectionEnabled()) {
        InstrumentationContext.get(LogEntry.class, AgentSpan.Context.class)
            .put(event, span.context());
      }
    }
  }
}
