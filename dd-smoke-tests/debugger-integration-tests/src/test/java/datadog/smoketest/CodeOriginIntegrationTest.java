package datadog.smoketest;

import static datadog.trace.api.DDTags.DD_CODE_ORIGIN_FRAME_FILE;
import static datadog.trace.api.DDTags.DD_CODE_ORIGIN_FRAME_LINE;
import static datadog.trace.api.DDTags.DD_CODE_ORIGIN_FRAME_METHOD;
import static datadog.trace.api.DDTags.DD_CODE_ORIGIN_FRAME_SIGNATURE;
import static org.junit.jupiter.api.Assertions.assertEquals;

import datadog.trace.api.DDTags;
import datadog.trace.test.agent.decoder.DecodedSpan;
import datadog.trace.test.util.NonRetryable;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@NonRetryable
public class CodeOriginIntegrationTest extends ServerAppDebuggerIntegrationTest {

  @BeforeEach
  public void beforeEach() {
    additionalJvmArgs.add("-Ddd.trace.enabled=true"); // explicitly enable tracer
    additionalJvmArgs.add("-Ddd.code.origin.for.spans.enabled=true");
  }

  @Test
  @DisplayName("testCodeOriginTraceAnnotation")
  void testCodeOriginTraceAnnotation() throws Exception {
    appUrl = startAppAndAndGetUrl();
    execute(appUrl, TRACED_METHOD_NAME);
    waitForInstrumentation(appUrl);
    execute(appUrl, TRACED_METHOD_NAME);
    AtomicBoolean codeOrigin = new AtomicBoolean();
    registerTraceListener(
        decodedTrace -> {
          for (DecodedSpan span : decodedTrace.getSpans()) {
            if (isTracedFullMethodSpan(span)) {
              if (span.getMeta().containsKey(DDTags.DD_CODE_ORIGIN_TYPE)) {
                assertEquals("entry", span.getMeta().get(DDTags.DD_CODE_ORIGIN_TYPE));
                assertEquals(
                    "ServerDebuggerTestApplication.java",
                    span.getMeta().get(DD_CODE_ORIGIN_FRAME_FILE));
                assertEquals("runTracedMethod", span.getMeta().get(DD_CODE_ORIGIN_FRAME_METHOD));
                assertEquals(
                    "(java.lang.String)", span.getMeta().get(DD_CODE_ORIGIN_FRAME_SIGNATURE));
                assertEquals("158", span.getMeta().get(DD_CODE_ORIGIN_FRAME_LINE));
                codeOrigin.set(true);
              }
            }
          }
        });
    processRequests(
        codeOrigin::get, () -> String.format("timeout codeOrigin=%s", codeOrigin.get()));
  }
}
