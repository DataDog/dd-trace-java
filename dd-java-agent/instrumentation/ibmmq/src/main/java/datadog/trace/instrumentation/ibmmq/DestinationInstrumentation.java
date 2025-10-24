package datadog.trace.instrumentation.ibmmq;

// import static
// datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.implementsInterface;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.api.datastreams.DataStreamsTags.Direction.INBOUND;
import static datadog.trace.api.datastreams.DataStreamsTags.Direction.OUTBOUND;
import static datadog.trace.api.datastreams.DataStreamsTags.create;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.ibm.mq.MQDestination;
import com.ibm.mq.MQException;
import com.ibm.mq.MQMessage;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.api.datastreams.DataStreamsContext;
import datadog.trace.api.datastreams.DataStreamsTags;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import net.bytebuddy.asm.Advice;

public final class DestinationInstrumentation
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {
  public DestinationInstrumentation() {}

  @Override
  public String instrumentedType() {
    return "com.ibm.mq.MQDestination";
  }
  // @Override
  // public ElementMatcher<TypeDescription> hierarchyMatcher() {
  //   return hasSuperType(named(hierarchyMarkerType()));
  // }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        named("put")
            .and(takesArgument(0, named("com.ibm.mq.MQMessage")))
            .and(takesArguments(2))
            .and(isPublic()),
        DestinationInstrumentation.class.getName() + "$ProducerAdvice");

    transformer.applyAdvice(
        named("get")
            .and(takesArgument(0, named("com.ibm.mq.MQMessage")))
            .and(takesArguments(3))
            .and(isPublic()),
        DestinationInstrumentation.class.getName() + "$ConsumerAdvice");
  }

  public static class ProducerAdvice {
    public static final CharSequence IBMMQ_PRODUCE = UTF8BytesString.create("ibmmqproduce");

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static AgentScope beforeSend(
        @Advice.Argument(0) final MQMessage message, @Advice.This final MQDestination destination) {

      System.out.println("producer entering beforesend");

      final AgentSpan span = startSpan("ibmmq", IBMMQ_PRODUCE);
      String destinationName;
      System.out.println("getting name");
      try {
        destinationName = destination.getName();
      } catch (MQException e) {
        destinationName = null;
      }

      System.out.println("name " + destinationName);

      if (destinationName != null) {
        final String prefix;
        if (destination.getClass().getName() == "com.ibm.mq.MQQueue") {
          prefix = "QUEUE:///";
        } else {
          prefix = "";
        }

        DataStreamsTags tags = create("ibmmq", OUTBOUND, prefix + destinationName);
        DataStreamsContext dsmContext = DataStreamsContext.fromTags(tags);
        AgentTracer.get().getDataStreamsMonitoring().setCheckpoint(span, dsmContext);
      }

      return null;
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void afterSend(
        @Advice.Enter final AgentScope scope, @Advice.Thrown final Throwable throwable) {
      return;
    }
  }

  public static class ConsumerAdvice {
    public static final CharSequence IBMMQ_CONSUME = UTF8BytesString.create("ibmmqconsume");

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static AgentScope beforeReceive(
        @Advice.Argument(0) final MQMessage message, @Advice.This final MQDestination destination) {

      System.out.println("consumer entering beforereceive");

      final AgentSpan span = startSpan("ibmmq", IBMMQ_CONSUME);
      String destinationName;
      System.out.println("getting name");
      try {
        destinationName = destination.getName();
      } catch (MQException e) {
        destinationName = null;
      }

      System.out.println("name " + destinationName);

      if (destinationName != null) {
        final String prefix;
        if (destination.getClass().getName() == "com.ibm.mq.MQQueue") {
          prefix = "QUEUE:///";
        } else {
          prefix = "";
        }
        DataStreamsTags tags = create("ibmmq", INBOUND, destinationName);
        DataStreamsContext dsmContext = DataStreamsContext.fromTags(tags);
        AgentTracer.get().getDataStreamsMonitoring().setCheckpoint(span, dsmContext);
      }
      return null;
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void afterReceive(
        @Advice.Enter final AgentScope scope, @Advice.Thrown final Throwable throwable) {
      return;
    }
  }
}
