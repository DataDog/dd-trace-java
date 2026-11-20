package datadog.trace.bootstrap.instrumentation.decorator;

import static org.junit.jupiter.api.Assertions.assertEquals;

import datadog.trace.api.DDTags;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.common.writer.ListWriter;
import datadog.trace.core.CoreTracer;
import datadog.trace.core.DDSpan;
import datadog.trace.test.junit.utils.config.WithConfig;
import datadog.trace.test.junit.utils.config.WithConfigExtension;
import java.net.URI;
import org.junit.jupiter.api.extension.ExtendWith;
import org.tabletest.junit.TableTest;

@ExtendWith(WithConfigExtension.class)
@WithConfig(key = "http.client.tag.query-string", value = "true")
class HttpClientQueryRedactionForkedTest {

  @TableTest({
    "Scenario          | Raw query                                   | Expected query               ",
    "Encoded ampersand | token=first%26SECOND_HALF&view=full         | <redacted>&view=full         ",
    "Encoded name      | t%6fken=first%26SECOND_HALF&view=full       | <redacted>&view=full         ",
    "Encoded JSON      | data=%7B%22token%22%3A%22first%26LAST%22%7D | 'data={<redacted>}'          ",
    "Bracketed escapes | q=[a%26b%23c]&t%6fken=first%26LAST          | q=[a%26b%23c]&<redacted>     ",
    "Safe delimiters   | q=one%26two%23three&view=full               | q=one%26two%23three&view=full"
  })
  void writtenClientSpanRedactsTheWholeSecret(String rawQuery, String expectedQuery)
      throws Exception {
    ListWriter writer = new ListWriter();
    CoreTracer tracer = CoreTracer.builder().writer(writer).build();
    try {
      DDSpan span = (DDSpan) tracer.startSpan("test", "http.request");
      TestClientDecorator decorator = new TestClientDecorator();
      decorator.afterStart(span);
      decorator.onRequest(span, URI.create("http://host/orders?" + rawQuery));
      span.finish();
      writer.waitForTraces(1);

      // ListWriter runs the normal tag post-processors, including query obfuscation.
      DDSpan writtenSpan = writer.firstTrace().get(0);
      assertEquals(expectedQuery, writtenSpan.getTag(DDTags.HTTP_QUERY));
      assertEquals("http://host/orders?" + expectedQuery, writtenSpan.getTag(Tags.HTTP_URL));
    } finally {
      tracer.close();
    }
  }

  private static final class TestClientDecorator extends HttpClientDecorator<URI, Integer> {
    @Override
    protected String[] instrumentationNames() {
      return new String[] {"test-http-client"};
    }

    @Override
    protected CharSequence component() {
      return "test-http-client";
    }

    @Override
    protected String method(URI request) {
      return "GET";
    }

    @Override
    protected URI url(URI request) {
      return request;
    }

    @Override
    protected int status(Integer response) {
      return response;
    }

    @Override
    protected String getRequestHeader(URI request, String headerName) {
      return null;
    }

    @Override
    protected String getResponseHeader(Integer response, String headerName) {
      return null;
    }
  }
}
