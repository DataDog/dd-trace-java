package datadog.smoketest;

import static org.junit.jupiter.api.Assertions.assertEquals;

import datadog.trace.api.DDTags;
import datadog.trace.test.agent.decoder.DecodedSpan;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

public class CodeOriginIntegrationTest extends ServerAppDebuggerIntegrationTest {

  private static final String DD_CODE_ORIGIN_FRAMES_0_FILE =
      String.format(DDTags.DD_CODE_ORIGIN_FRAME, 0, "file");
  private static final String DD_CODE_ORIGIN_FRAMES_0_METHOD =
      String.format(DDTags.DD_CODE_ORIGIN_FRAME, 0, "method");
  private static final String DD_CODE_ORIGIN_FRAMES_0_SIGNATURE =
      String.format(DDTags.DD_CODE_ORIGIN_FRAME, 0, "signature");
  private static final String DD_CODE_ORIGIN_FRAMES_0_LINE =
      String.format(DDTags.DD_CODE_ORIGIN_FRAME, 0, "line");

  @Override
  protected ProcessBuilder createProcessBuilder(Path logFilePath, String... params) {
    List<String> commandParams = getDebuggerCommandParams();
    commandParams.add("-Ddd.trace.enabled=true"); // explicitly enable tracer
    commandParams.add("-Ddd.code.origin.for.spans.enabled=true");
    return ProcessBuilderHelper.createProcessBuilder(
        commandParams, logFilePath, getAppClass(), params);
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
                    span.getMeta().get(DD_CODE_ORIGIN_FRAMES_0_FILE));
                assertEquals("runTracedMethod", span.getMeta().get(DD_CODE_ORIGIN_FRAMES_0_METHOD));
                assertEquals(
                    "(java.lang.String)", span.getMeta().get(DD_CODE_ORIGIN_FRAMES_0_SIGNATURE));
                assertEquals("134", span.getMeta().get(DD_CODE_ORIGIN_FRAMES_0_LINE));
                codeOrigin.set(true);
              }
            }
          }
        });
    processRequests(codeOrigin::get);
  }
}
