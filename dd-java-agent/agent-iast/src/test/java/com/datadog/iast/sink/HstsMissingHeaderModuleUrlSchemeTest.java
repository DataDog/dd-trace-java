package com.datadog.iast.sink;

import static com.datadog.iast.model.VulnerabilityType.HSTS_HEADER_MISSING;
import static com.datadog.iast.overhead.Operations.REPORT_VULNERABILITY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.datadog.iast.Dependencies;
import com.datadog.iast.IastRequestContext;
import com.datadog.iast.Reporter;
import com.datadog.iast.overhead.OverheadController;
import datadog.trace.api.KnownTags;
import datadog.trace.api.TagMap;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import org.junit.jupiter.api.Test;
import org.tabletest.junit.TableTest;

class HstsMissingHeaderModuleUrlSchemeTest {

  @TableTest({
    "Scenario                    | url                 | scheme | forwardedProto | expected",
    "Legacy HTTPS                | https://localhost/a |        |                | true    ",
    "Legacy HTTP                 | http://localhost/a  |        |                | false   ",
    "Legacy forwarded HTTPS      | http://localhost/a  |        | https          | true    ",
    "OTel HTTPS                  |                     | https  |                | true    ",
    "OTel uppercase HTTPS        |                     | HTTPS  |                | true    ",
    "OTel HTTP                   |                     | http   |                | false   ",
    "OTel forwarded HTTPS        |                     | http   | https          | true    ",
    "OTel HTTPS forwarded HTTP   |                     | https  | http           | true    ",
    "No URL attributes           |                     |        |                | false   ",
    "No URL attributes forwarded |                     |        | https          | false   "
  })
  void detectsHttps(String url, String scheme, String forwardedProto, boolean expected) {
    assertEquals(expected, HstsMissingHeaderModuleImpl.isHttps(url, scheme, forwardedProto));
  }

  @Test
  void reportsMissingHstsUsingUrlSchemeWithoutLegacyUrl() {
    Dependencies dependencies = mock(Dependencies.class);
    Reporter reporter = mock(Reporter.class);
    OverheadController overheadController = mock(OverheadController.class);
    when(dependencies.getReporter()).thenReturn(reporter);
    when(dependencies.getOverheadController()).thenReturn(overheadController);

    IastRequestContext context = mock(IastRequestContext.class);
    when(context.getContentType()).thenReturn("text/html");

    TagMap tags = TagMap.create();
    tags.put(KnownTags.URL_SCHEME_NAME, "https");
    tags.put(Tags.HTTP_STATUS, 200);
    AgentSpan span = mock(AgentSpan.class);
    when(span.getTags()).thenReturn(tags);
    when(overheadController.consumeQuota(REPORT_VULNERABILITY, span, HSTS_HEADER_MISSING))
        .thenReturn(true);

    new HstsMissingHeaderModuleImpl(dependencies).onRequestEnd(context, span);

    verify(reporter)
        .report(eq(span), argThat(vulnerability -> vulnerability.getType() == HSTS_HEADER_MISSING));
  }
}
