package datadog.trace.instrumentation.kafka_clients38;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;

import datadog.trace.api.datastreams.StatsPoint;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import net.bytebuddy.asm.Advice;

public class PayloadSizeAdvice {

  /**
   * Instrumentation for the method KafkaProducer.ensureValidRecordSize that is called as part of
   * sending a kafka payload. This gives us access to an estimate of the payload size "for free",
   * that we send as a metric.
   */
  @Advice.OnMethodEnter(suppress = Throwable.class)
  public static void onEnter(@Advice.Argument(value = 0) int estimatedPayloadSize) {
    StatsPoint saved = activeSpan().context().getPathwayContext().getSavedStats();
    if (saved != null) {
      // create new stats including the payload size
      StatsPoint updated =
          new StatsPoint(
              saved.getTags(),
              saved.getHash(),
              saved.getParentHash(),
              saved.getAggregationHash(),
              saved.getTimestampNanos(),
              saved.getPathwayLatencyNano(),
              saved.getEdgeLatencyNano(),
              estimatedPayloadSize,
              saved.getServiceNameOverride());
      // then send the point
      AgentTracer.get().getDataStreamsMonitoring().add(updated);
    }
  }
}
