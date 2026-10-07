package com.datadog.appsec.api.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;

import com.datadog.appsec.AppSecSystem;
import datadog.communication.ddagent.DDAgentFeaturesDiscovery;
import datadog.communication.ddagent.SharedCommunicationObjects;
import datadog.metrics.api.Monitoring;
import datadog.remoteconfig.ConfigurationPoller;
import datadog.trace.api.Config;
import datadog.trace.api.gateway.RequestContextSlot;
import datadog.trace.bootstrap.ActiveSubsystems;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentSpanContext;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.bootstrap.instrumentation.api.SpanPostProcessor;
import datadog.trace.common.writer.ListWriter;
import datadog.trace.core.CoreTracer;
import datadog.trace.core.DDSpan;
import datadog.trace.lambda.LambdaAppSecHandler;
import datadog.trace.test.junit.utils.config.WithConfig;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@WithConfig(key = "api-security.enabled", value = "true")
@WithConfig(key = "api-security.sample.delay", value = "0")
class LambdaApiSecurityForkedTest {

  private static final String REQUEST_SCHEMA_TAG = "_dd.appsec.s.req.body";
  private static final String RESPONSE_SCHEMA_TAG = "_dd.appsec.s.res.body";
  private static final String RESPONSE_HEADERS_SCHEMA_TAG = "_dd.appsec.s.res.headers";

  private AgentTracer.TracerAPI originalTracer;
  private CoreTracer tracer;
  private ListWriter writer;

  @BeforeEach
  void setUp() {
    originalTracer = AgentTracer.get();
    writer = new ListWriter();
    tracer = CoreTracer.builder().writer(writer).build();
    AgentTracer.forceRegister(tracer);
    AppSecSystem.start(
        tracer.getSubscriptionService(RequestContextSlot.APPSEC), sharedCommunicationObjects());
  }

  @AfterEach
  void tearDown() {
    AppSecSystem.stop();
    // stop() leaves the flag set; reset it so the state cannot leak between tests
    ActiveSubsystems.APPSEC_ACTIVE = false;
    SpanPostProcessor.Holder.INSTANCE = SpanPostProcessor.Holder.NOOP;
    AgentTracer.forceRegister(originalTracer);
    tracer.close();
  }

  @Test
  void generatesSchemasAndInfersSuccessForHttpApiV2Invocation() throws Exception {
    String eventJson =
        "{"
            + "\"version\":\"2.0\","
            + "\"routeKey\":\"POST /api/users/{id}\","
            + "\"rawPath\":\"/api/users/123\","
            + "\"headers\":{\"content-type\":\"application/json\","
            + "\"host\":\"api.example.com\"},"
            + "\"pathParameters\":{\"id\":\"123\"},"
            + "\"body\":\"{\\\"name\\\":\\\"Ada\\\",\\\"age\\\":37}\","
            + "\"requestContext\":{\"domainName\":\"api.example.com\","
            + "\"http\":{\"method\":\"POST\",\"path\":\"/api/users/123\","
            + "\"sourceIp\":\"203.0.113.1\"}}"
            + "}";
    ByteArrayInputStream input =
        new ByteArrayInputStream(eventJson.getBytes(StandardCharsets.UTF_8));

    AgentSpanContext parent = LambdaAppSecHandler.processRequestStart(input);
    assertNotNull(parent);
    AgentSpan span = AgentTracer.startSpan("aws-lambda", "aws.lambda.invoke", parent);

    String responseJson = "{\"id\":123,\"active\":true}";
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    output.write(responseJson.getBytes(StandardCharsets.UTF_8));

    LambdaAppSecHandler.processResponseData(span, output);
    LambdaAppSecHandler.processRequestEnd(span);
    // TraceProcessingWorker invokes this after request end in production. ListWriter deliberately
    // bypasses that worker, so invoke the registered real post-processor here.
    SpanPostProcessor.Holder.INSTANCE.process(span, () -> false);
    span.finish();

    writer.waitForTraces(1);
    DDSpan finishedSpan = writer.firstTrace().get(0);
    assertEquals(200, ((Number) finishedSpan.getTag("http.status_code")).intValue());
    assertNotNull(finishedSpan.getTag(REQUEST_SCHEMA_TAG));
    assertNotNull(finishedSpan.getTag(RESPONSE_SCHEMA_TAG));
    assertNotNull(finishedSpan.getTag(RESPONSE_HEADERS_SCHEMA_TAG));
  }

  private SharedCommunicationObjects sharedCommunicationObjects() {
    SharedCommunicationObjects sco =
        new SharedCommunicationObjects() {
          @Override
          public ConfigurationPoller configurationPoller(Config config) {
            return mock(ConfigurationPoller.class);
          }
        };
    sco.agentHttpClient = mock(OkHttpClient.class);
    sco.monitoring = mock(Monitoring.class);
    sco.setFeaturesDiscovery(mock(DDAgentFeaturesDiscovery.class));
    return sco;
  }
}
