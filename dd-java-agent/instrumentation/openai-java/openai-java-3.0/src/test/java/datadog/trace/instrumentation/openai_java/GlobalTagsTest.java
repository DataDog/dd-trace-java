package datadog.trace.instrumentation.openai_java;

import static datadog.trace.agent.test.utils.TraceUtils.runUnderTrace;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.api.config.GeneralConfig;
import datadog.trace.core.DDSpan;
import datadog.trace.test.junit.utils.config.WithConfig;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Verifies that global tags are propagated to LLMObs span tags. */
// Keep coverage of the legacy global-tags configuration used by the original test.
@SuppressWarnings("deprecation")
@WithConfig(key = GeneralConfig.GLOBAL_TAGS, value = "team:backend,owner:ml-platform")
class GlobalTagsTest extends OpenAiTest {
  @Test
  void globalDdTagsAreIncludedInOpenAiLlmObsSpanTags() throws Exception {
    runUnderTrace(
        "parent",
        () -> openAiClient.chat().completions().create(chatCompletionCreateParams(false)));
    writer.waitForTraces(1);
    Optional<DDSpan> openAiSpan =
        writer.firstTrace().stream()
            .filter(span -> "openai.request".contentEquals(span.getOperationName()))
            .findFirst();
    assertTrue(openAiSpan.isPresent(), "Expected an OpenAI span");
    assertEquals("backend", openAiSpan.get().getTag("_ml_obs_tag.team"));
    assertEquals("ml-platform", openAiSpan.get().getTag("_ml_obs_tag.owner"));
  }
}
