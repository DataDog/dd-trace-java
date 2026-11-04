package datadog.trace.bootstrap.instrumentation.decorator;

import static datadog.trace.bootstrap.instrumentation.api.Tags.COMPONENT;
import static datadog.trace.bootstrap.instrumentation.api.Tags.SPAN_KIND;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import datadog.trace.api.DDTags;
import datadog.trace.api.TagMap;
import datadog.trace.bootstrap.instrumentation.api.AgentSpanContext;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import java.util.Map;
import org.junit.jupiter.api.Disabled;
import org.tabletest.junit.TableTest;

class DBTypeProcessingDatabaseClientDecoratorTest extends ClientDecoratorTest {

  @Disabled("https://github.com/DataDog/dd-trace-java/pull/5213")
  @TableTest({
    "scenario      | serviceName  ",
    "test service  | test-service ",
    "other service | other-service",
    "no service    |              "
  })
  void testDbTypeProcessingAfterStart(String serviceName) {
    AgentSpanContext spanContext = mock(AgentSpanContext.class);
    when(span.spanContext()).thenReturn(spanContext);
    DBTypeProcessingDatabaseClientDecorator<Map<String, String>> decorator =
        newDecorator(serviceName);

    // when
    decorator.afterStart(span);

    // then
    if (serviceName != null) {
      verify(span).setServiceName(serviceName, "test-component");
    }
    verify(span).setMeasured(true);
    verify(span).setTag(COMPONENT, "test-component");
    verify(span).spanContext();
    verify(spanContext).setIntegrationName("test-component");
    verify(span).setTag(TagMap.Entry.create(SPAN_KIND, "client"));
    verify(span).setSpanType("test-type");
    verify(span).setServiceName("test-db");
    verify(span).setOperationName(UTF8BytesString.create("test-db.query"));
    // is this really necessary or a waste of space and cycles?
    verify(span).setTag("db.type", "test-db");
    verify(span).setMetric(DDTags.ANALYTICS_SAMPLE_RATE, 1.0);
    verifyNoMoreInteractions(span, spanContext);
  }

  @Override
  protected DBTypeProcessingDatabaseClientDecorator<Map<String, String>> newDecorator() {
    return newDecorator("test-service");
  }

  @Override
  protected DBTypeProcessingDatabaseClientDecorator<Map<String, String>> newDecorator(
      String serviceName) {
    return new DBTypeProcessingDatabaseClientDecorator<Map<String, String>>() {
      @Override
      protected String[] instrumentationNames() {
        return new String[] {"test1", "test2"};
      }

      @Override
      protected String service() {
        return serviceName;
      }

      @Override
      protected CharSequence component() {
        return "test-component";
      }

      @Override
      protected CharSequence spanType() {
        return "test-type";
      }

      @Override
      protected String dbType() {
        return "test-db";
      }

      @Override
      protected String dbUser(Map<String, String> map) {
        return map.get("user");
      }

      @Override
      protected String dbInstance(Map<String, String> map) {
        return map.get("instance");
      }

      @Override
      protected String dbHostname(Map<String, String> map) {
        return map.get("hostname");
      }

      @Override
      protected boolean traceAnalyticsDefault() {
        return true;
      }
    };
  }
}
