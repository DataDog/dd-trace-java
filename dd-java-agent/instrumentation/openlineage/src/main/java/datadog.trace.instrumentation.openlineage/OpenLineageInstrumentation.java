package datadog.trace.instrumentation.openlineage;

import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.util.Strings;
import io.openlineage.client.OpenLineage;
import net.bytebuddy.asm.Advice;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static io.openlineage.client.OpenLineage.RunEvent.EventType.*;
import static net.bytebuddy.matcher.ElementMatchers.*;

public class OpenLineageInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForKnownTypes {


  public OpenLineageInstrumentation() {
    super("openlineage");
  }

  @Override
  protected boolean defaultEnabled() {
    return false;
  }

  @Override
  public String[] knownMatchingTypes() {
    return new String[]{
        "io.openlineage.client.transports.Transport",
        "io.openlineage.client.transports.ConsoleTransport",
        "io.openlineage.client.transports.FileTransport",
        "io.openlineage.client.transports.HttpTransport",
        "io.openlineage.client.transports.KafkaTransport",
        "io.openlineage.client.transports.KinesisTransport",
        "io.openlineage.client.transports.NoopTransport"
    };
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    // TracerProvider OpenTelemetry.getTracerProvider()
    transformer.applyAdvice(
        isMethod()
            .and(named("emit"))
            .and(takesArgument(0, OpenLineage.RunEvent.class)),
        OpenLineageInstrumentation.class.getName() + "$RunEmitAdvice");

  }

  public static class RunEmitAdvice {
    private static final Map<UUID, OpenLineage.RunEvent> startedSpans = new HashMap<>();
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void enter(@Advice.Argument(0) OpenLineage.RunEvent runEvent) {
      // We may want a spark specific version of this to capture the parent run on app start?
      if (runEvent.getEventType() == START) {
        startedSpans.put(runEvent.getRun().getRunId(), runEvent);
      } else if( runEvent.getEventType() == COMPLETE || runEvent.getEventType() == FAIL || runEvent.getEventType() == ABORT ) {
        AgentSpan span;
        OpenLineage.RunEvent startEvent = startedSpans.get(runEvent.getRun().getRunId());
        if( startEvent == null ) {
          // Create a span with the complete event only, start time will be missing
          span = startSpan( "OpenLineage", "RunEvent", runEvent.getEventTime().getNano());
        } else {
          // Start the span using the start event
          span = startSpan( "OpenLineage", "RunEvent", startEvent.getEventTime().getNano());
        }
      }
    }
  }
}
