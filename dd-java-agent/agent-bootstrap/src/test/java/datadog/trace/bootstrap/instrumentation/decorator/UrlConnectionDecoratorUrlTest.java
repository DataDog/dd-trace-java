package datadog.trace.bootstrap.instrumentation.decorator;

import static datadog.trace.api.config.GeneralConfig.TRACE_OTEL_SEMANTICS_ENABLED;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import datadog.trace.api.KnownTags;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.test.junit.utils.config.WithConfig;
import java.net.URI;
import org.junit.jupiter.api.Test;

@WithConfig(key = TRACE_OTEL_SEMANTICS_ENABLED, value = "false")
class UrlConnectionDecoratorUrlTest {
  @Test
  @WithConfig(key = "http.client.tag.query-string", value = "false")
  void datadogUrlConnectionKeepsOriginalUrl() {
    AgentSpan span = mock(AgentSpan.class, CALLS_REAL_METHODS);
    String url = "http://u:p@host:80/a?q=1#fragment";

    UrlConnectionDecorator.DECORATE.onURI(span, URI.create(url));

    verify(span).setTag(KnownTags.HTTP_URL_ID, url);
    verify(span, never()).setTag(eq(KnownTags.HTTP_QUERY_STRING_ID), anyString());
  }
}
