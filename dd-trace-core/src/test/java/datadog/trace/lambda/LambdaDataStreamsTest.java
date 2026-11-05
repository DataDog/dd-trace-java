package datadog.trace.lambda;

import static datadog.trace.api.datastreams.DataStreamsTags.Direction.OUTBOUND;
import static datadog.trace.lambda.LambdaTriggerExtractorTest.fixture;
import static java.util.Collections.singletonMap;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import datadog.trace.api.Config;
import datadog.trace.api.datastreams.DataStreamsContext;
import datadog.trace.api.datastreams.DataStreamsTags;
import datadog.trace.api.datastreams.PathwayContext;
import datadog.trace.api.datastreams.StatsPoint;
import datadog.trace.api.time.SystemTimeSource;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.common.sampling.Sampler;
import datadog.trace.common.writer.ListWriter;
import datadog.trace.core.CoreTracer;
import datadog.trace.core.DDCoreJavaSpecification;
import datadog.trace.core.datastreams.DataStreamsMonitoring;
import datadog.trace.core.datastreams.DataStreamsPropagator;
import datadog.trace.core.datastreams.DefaultPathwayContext;
import datadog.trace.test.junit.utils.config.WithConfig;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.tabletest.junit.TableTest;

@WithConfig(key = "data.streams.enabled", value = "true")
class LambdaDataStreamsTest extends DDCoreJavaSpecification {
  @TableTest({
    "dropTrace",
    "false    ",
    "true     "
  })
  void allRecordsAreCountedRegardlessOfTraceRetention(boolean dropTrace) throws Exception {
    DataStreamsMonitoring dsm = mock(DataStreamsMonitoring.class);
    when(dsm.newPathwayContext())
        .thenAnswer(call -> new DefaultPathwayContext(SystemTimeSource.INSTANCE, null));
    when(dsm.propagator())
        .thenReturn(new DataStreamsPropagator(dsm, SystemTimeSource.INSTANCE, new ThreadLocal<>()));
    ListWriter writer = new ListWriter();
    Sampler sampler = mock(Sampler.class);
    when(sampler.sample(any())).thenReturn(!dropTrace);
    CoreTracer tracer =
        tracerBuilder().writer(writer).sampler(sampler).dataStreamsMonitoring(dsm).build();
    AgentTracer.TracerAPI previous = AgentTracer.get();
    AgentTracer.forceRegister(tracer);
    try {
      PathwayContext first = producer("first");
      PathwayContext second = producer("second");

      Map<String, Object> event = singletonMap("Records", Arrays.asList(sqs(first), sqs(second)));
      LambdaInvocation invocation =
          LambdaInvocation.start(tracer, Config.get(), event, "req", "fn", "1", null);
      PathwayContext continued = invocation.span().spanContext().getPathwayContext();
      assertTrue(continued.isStarted());
      ArgumentCaptor<StatsPoint> points = ArgumentCaptor.forClass(StatsPoint.class);
      verify(dsm, times(2)).add(points.capture());
      assertEquals(first.getHash(), points.getAllValues().get(0).getParentHash());
      assertEquals(second.getHash(), points.getAllValues().get(1).getParentHash());
      assertEquals(2, points.getAllValues().get(0).getPayloadSizeBytes());
      assertEquals(points.getAllValues().get(0).getHash(), continued.getHash());
      try (datadog.context.ContextScope scope = invocation.activate()) {
        AgentSpan child = tracer.startSpan("test", "child");
        assertEquals(continued.getHash(), child.spanContext().getPathwayContext().getHash());
        child.finish();
      }
      invocation.onExit(null, null);
      invocation.finish();
    } finally {
      AgentTracer.forceRegister(previous);
      tracer.close();
    }
  }

  @TableTest({
    "fixture                | type       ",
    "sqs_event              | sqs        ",
    "sns_event              | sns        ",
    "kinesis_event          | kinesis    ",
    "msk_event_with_headers | kafka      ",
    "eventbridge_event      | eventbridge"
  })
  void supportedSourcesProduceInputs(String name, String type) throws Exception {
    List<LambdaDataStreams.Input> inputs =
        LambdaDataStreams.inputs(Config.get(), LambdaEventData.json(fixture(name)));
    assertTrue(!inputs.isEmpty());
    for (LambdaDataStreams.Input input : inputs) {
      assertEquals("type:" + type, input.tags.getType());
      assertEquals("direction:in", input.tags.getDirection());
      assertTrue(input.bytes >= 0);
    }
  }

  @Test
  void exchangeAndKafkaGroupConfigurationTakePrecedence() {
    Map<String, Object> event =
        LambdaEventData.json(
            "{\"source\":\"custom\",\"detail-type\":\"detail\",\"detail\":{},\"resources\":[\"arn:aws:events:us-east-1:123:rule/payload-bus/rule\"]}");
    assertEquals(
        "exchange:payload-bus",
        LambdaDataStreams.inputs(Config.get(), event).get(0).tags.getExchange());
    event.remove("resources");
    assertEquals(
        "exchange:default",
        LambdaDataStreams.inputs(Config.get(), event).get(0).tags.getExchange());
    Properties properties = new Properties();
    properties.setProperty("dsm.exchange.name", "configured");
    properties.setProperty("dsm.kafka.group", "group");
    Config config = Config.get(properties);
    assertEquals(
        "exchange:configured", LambdaDataStreams.inputs(config, event).get(0).tags.getExchange());
    Map<String, Object> kafka =
        LambdaEventData.json(
            "{\"records\":{\"topic-0\":{\"0\":{\"topic\":\"topic\",\"value\":\"w6k=\",\"headers\":[]},\"1\":{\"topic\":\"topic\",\"value\":\"Yg==\",\"headers\":[]}}}}");
    List<LambdaDataStreams.Input> inputs = LambdaDataStreams.inputs(config, kafka);
    assertEquals(2, inputs.size());
    assertEquals("group:group", inputs.get(0).tags.getGroup());
    assertEquals(2, inputs.get(0).bytes);
    assertEquals(1, inputs.get(1).bytes);
  }

  private PathwayContext producer(String topic) {
    PathwayContext pathway = new DefaultPathwayContext(SystemTimeSource.INSTANCE, null);
    pathway.setCheckpoint(
        DataStreamsContext.fromTags(DataStreamsTags.create("sqs", OUTBOUND, topic)), point -> {});
    return pathway;
  }

  private Map<String, Object> sqs(PathwayContext pathway) throws Exception {
    Map<String, Object> record = new HashMap<>();
    record.put("eventSource", "aws:sqs");
    record.put("eventSourceARN", "arn:aws:sqs:us-east-1:123:queue");
    record.put("body", "é");
    Map<String, Object> attribute = new HashMap<>();
    attribute.put("dataType", "String");
    attribute.put(
        "stringValue",
        LambdaEventData.serialize(
            singletonMap(PathwayContext.PROPAGATION_KEY_BASE64, pathway.encode())));
    record.put("messageAttributes", singletonMap("_datadog", attribute));
    return record;
  }
}
