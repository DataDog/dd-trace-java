package datadog.trace.instrumentation.kafka_streams;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.bootstrap.instrumentation.api.PathwayContext;
import datadog.trace.instrumentation.kafka_clients.TextMapExtractAdapter;
import net.bytebuddy.asm.Advice;
import org.apache.kafka.clients.producer.ProducerRecord;

@AutoService(Instrumenter.class)
public class StreamsProducerInstrumentation extends Instrumenter.Tracing
    implements Instrumenter.ForSingleType {

  public StreamsProducerInstrumentation() {
    super("kafka", "kafka-streams");
  }
  @Override
  public String instrumentedType() {
    return "org.apache.kafka.streams.processor.internals.StreamsProducer";
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
        packageName + ".TopologyContext",
        "datadog.trace.instrumentation.kafka_clients.TextMapExtractAdapter",
        "datadog.trace.instrumentation.kafka_clients.Base64Decoder",
    };
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(isMethod().and(named("send")),
        StreamsProducerInstrumentation.class.getName() + "$SendAdvice");
  }

  public static class SendAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void enter(
        @Advice.Argument(value = 0, readOnly = false) ProducerRecord record
    ) {
      if (TopologyContext.isTargetTopic(record.topic())) {
        PathwayContext context = AgentTracer.get().extractBinaryPathwayContext(record.headers(),
            TextMapExtractAdapter.GETTER);
        AgentTracer.activeSpan().setPathwayContext(context);
      } else {
        // A hack to force the downstream instrumentation to skip checkpointing
        record.headers().add("dsm_ignore", null);
      }
    }
  }

  @Advice.OnMethodExit(suppress = Throwable.class)
  public static void leave(
      @Advice.Argument(value = 0, readOnly = false) ProducerRecord record) {
    record.headers().remove("no_dsm_checkpoint");
  }
}
