package datadog.trace.instrumentation.pubsub;

import com.google.pubsub.v1.PubsubMessage;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.bootstrap.instrumentation.api.PathwayContext;
import net.bytebuddy.asm.Advice;

import java.util.LinkedHashMap;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.*;
import static datadog.trace.core.datastreams.TagsProcessor.TYPE_TAG;
import static datadog.trace.instrumentation.pubsub.MessageReceiverDecorator.DECORATE;

public class Dumpster {

  public static final class ReceiveMessage {
    @Advice.OnMethodEnter
    public static void before(
        @Advice.Argument(0) PubsubMessage message
    ) {
      System.out.println("====================> before: " + message.getAttributesMap());
      final AgentSpan.Context spanContext = propagate().extract(message, TextMapExtractAdapter.GETTER);
      AgentSpan span = AgentTracer.startSpan("pubsub", spanContext);
      PathwayContext pathwayContext =
          propagate().extractBinaryPathwayContext(message, TextMapExtractAdapter.GETTER);
      span.mergePathwayContext(pathwayContext);

      LinkedHashMap<String, String> sortedTags = new LinkedHashMap<>();
      sortedTags.put(TYPE_TAG, "pubsub");
      AgentTracer.get().setDataStreamCheckpoint(span, sortedTags);

      DECORATE.afterStart(span);

      activateNext(span);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    public static void after() {
      System.out.println("====================> after");
      closePrevious(true);
    }
  }
}
