package datadog.trace.lambda;

import static datadog.trace.api.config.GeneralConfig.TRACE_OTEL_SEMANTICS_ENABLED;
import static datadog.trace.api.config.TraceInstrumentationConfig.HTTP_SERVER_TAG_QUERY_STRING;
import static datadog.trace.api.config.TraceInstrumentationConfig.OBFUSCATION_QUERY_STRING_REGEXP;
import static datadog.trace.core.CoreSpan.OWN_PROPAGATION_TAGS;
import static datadog.trace.core.MetadataConsumer.NO_OP;
import static datadog.trace.lambda.LambdaEventParser.parseEvent;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import datadog.trace.api.DDSpanTypes;
import datadog.trace.bootstrap.instrumentation.api.TagContext;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.common.writer.ListWriter;
import datadog.trace.core.CoreTracer;
import datadog.trace.core.DDSpan;
import datadog.trace.core.tagprocessor.TagsPostProcessorFactory;
import datadog.trace.lambda.LambdaEventParser.LambdaRequestData;
import datadog.trace.test.junit.utils.config.WithConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@WithConfig(key = TRACE_OTEL_SEMANTICS_ENABLED, value = "true")
@WithConfig(key = HTTP_SERVER_TAG_QUERY_STRING, value = "true")
class LambdaHttpOtelSemanticsForkedTest {
  private CoreTracer tracer;

  @BeforeEach
  void setUp() {
    // Rebuild the chain so each method's obfuscation configuration is used.
    TagsPostProcessorFactory.reset();
    tracer = CoreTracer.builder().writer(new ListWriter()).build();
  }

  @AfterEach
  void tearDown() {
    tracer.close();
  }

  @Test
  void lambdaQueryIsObfuscatedWithDefaultPattern() {
    assertSanitizedLambdaQuery(
        "https://api.example.com/orders?<redacted>&view=full", "<redacted>&view=full");
  }

  @Test
  @WithConfig(key = OBFUSCATION_QUERY_STRING_REGEXP, value = "token=[^&]+")
  void lambdaQueryIsObfuscatedWithCustomPattern() {
    assertSanitizedLambdaQuery(
        "https://api.example.com/orders?<redacted>&view=full", "<redacted>&view=full");
  }

  @Test
  @WithConfig(key = OBFUSCATION_QUERY_STRING_REGEXP, value = "(?<=a)b")
  void lambdaQueryIsDroppedWhenPatternCannotCompile() {
    assertSanitizedLambdaQuery("https://api.example.com/orders", null);
  }

  @Test
  @WithConfig(key = HTTP_SERVER_TAG_QUERY_STRING, value = "false")
  void lambdaOmitsQueryAttributesWhenCaptureIsDisabled() {
    TagContext parent = lambdaHttpContext();

    assertNull(parent.getTags().get("url.query"));
    assertEquals("https://api.example.com/orders", parent.getTags().get(Tags.HTTP_URL));
  }

  private void assertSanitizedLambdaQuery(String expectedUrl, String expectedQuery) {
    TagContext parent = lambdaHttpContext();
    assertEquals("token=secret&view=full", parent.getTags().get("url.query"));
    DDSpan span = (DDSpan) tracer.startSpan("test", "aws.lambda", parent);
    span.setSpanType(DDSpanTypes.HTTP_SERVER);
    span.setTag(Tags.SPAN_KIND, Tags.SPAN_KIND_SERVER);
    span.processTagsAndBaggage(NO_OP, OWN_PROPAGATION_TAGS);

    assertEquals(expectedQuery, span.getTag("url.query"));
    assertEquals(expectedUrl, span.getTag(Tags.HTTP_URL).toString());
  }

  private TagContext lambdaHttpContext() {
    LambdaRequestData request =
        parseEvent(
            "{\"version\":\"2.0\",\"rawPath\":\"/orders\","
                + "\"rawQueryString\":\"token=secret&view=full\","
                + "\"headers\":{\"host\":\"api.example.com\"},"
                + "\"requestContext\":{\"domainName\":\"api.example.com\","
                + "\"http\":{\"method\":\"GET\",\"path\":\"/orders\"}}}");
    TagContext parent = new TagContext();
    LambdaAppSecHandler.applyHttpTags(
        parent, request, new LambdaURIDataAdapter(request.rawUri, request.headers, request.host));
    return parent;
  }
}
