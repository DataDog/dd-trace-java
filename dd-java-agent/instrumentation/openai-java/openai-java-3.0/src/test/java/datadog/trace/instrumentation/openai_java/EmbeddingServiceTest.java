package datadog.trace.instrumentation.openai_java;

import static datadog.trace.agent.test.utils.TraceUtils.runUnderTrace;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.openai.core.http.HttpResponseFor;
import com.openai.models.embeddings.CreateEmbeddingResponse;
import com.openai.models.embeddings.EmbeddingCreateParams;
import datadog.trace.api.llmobs.LLMObs;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.core.DDSpan;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.tabletest.junit.TableTest;

class EmbeddingServiceTest extends OpenAiTest {
  @TableTest({
    "scenario | json ",
    "typed    | false",
    "JSON     | true "
  })
  void createEmbeddingTest(boolean json) {
    EmbeddingCreateParams params = embeddingCreateParams(json);

    CreateEmbeddingResponse resp =
        runUnderTrace("parent", () -> openAiClient.embeddings().create(params));

    assertNotNull(resp);
    assertEmbeddingTrace();
  }

  @TableTest({
    "scenario | json ",
    "typed    | false",
    "JSON     | true "
  })
  void createEmbeddingTestWithRawResponse(boolean json) {
    EmbeddingCreateParams params = embeddingCreateParams(json);

    try (HttpResponseFor<CreateEmbeddingResponse> resp =
        runUnderTrace("parent", () -> openAiClient.embeddings().withRawResponse().create(params))) {

      assertNotNull(resp);
      assertTrue(resp.parse().isValid()); // force response parsing, so it sets all the tags
      assertEmbeddingTrace();
    }
  }

  private void assertEmbeddingTrace() {
    Map<String, Object> tags = new HashMap<>();
    tags.put("_ml_obs_tag.span.kind", "embedding");
    tags.put("_ml_obs_tag.model_provider", "openai");
    tags.put("_ml_obs_tag.model_name", "text-embedding-ada-002-v2");
    tags.put("_ml_obs_tag.input", List.class);
    tags.put("_ml_obs_tag.metadata", Map.class);
    tags.put("_ml_obs_tag.output", "[1 embedding(s) returned with size 1536]");
    tags.put("_ml_obs_tag.parent_id", "undefined");
    tags.put("_ml_obs_tag.sampling_decision", "1");
    tags.put("_ml_obs_tag.sample_rate", "1");
    tags.put("_ml_obs_tag.ml_app", String.class);
    tags.put("gen_ai.operation.name", "embedding");
    tags.put("gen_ai.request.model", "text-embedding-ada-002-v2");
    tags.put("gen_ai.provider.name", "openai");
    tags.put("gen_ai.application.name", String.class);
    tags.put("gen_ai.usage.input_tokens", Double.class);
    tags.put("gen_ai.usage.total_tokens", Double.class);
    tags.put("_dd.llmobs.artificial_gen_ai_tags", "true");
    tags.put("_ml_obs_tag.service", String.class);
    tags.put(CommonTags.DDTRACE_VERSION, String.class);
    tags.put(CommonTags.SOURCE, "integration");
    tags.put(CommonTags.INTEGRATION, "openai");
    tags.put(CommonTags.ERROR, 0);
    tags.put("_ml_obs_metric.input_tokens", Long.class);
    tags.put("_ml_obs_metric.total_tokens", Long.class);
    tags.put("openai.request.method", "POST");
    tags.put("openai.request.endpoint", "/v1/embeddings");
    tags.put("openai.api_base", openAiBaseApi);
    tags.put("openai.organization.ratelimit.requests.limit", 10000);
    tags.put("openai.organization.ratelimit.requests.remaining", Integer.class);
    tags.put("openai.organization.ratelimit.tokens.limit", 10000000);
    tags.put("openai.organization.ratelimit.tokens.remaining", Integer.class);
    tags.put(CommonTags.OPENAI_REQUEST_MODEL, "text-embedding-ada-002");
    tags.put(CommonTags.OPENAI_RESPONSE_MODEL, "text-embedding-ada-002-v2");
    tags.put(CommonTags.OPENAI_ORGANIZATION, "datadog-staging");
    tags.put(Tags.COMPONENT, "openai");
    tags.put(Tags.SPAN_KIND, Tags.SPAN_KIND_CLIENT);
    DDSpan span = assertOpenAiTrace("createEmbedding", "/v1/embeddings", tags);
    List<LLMObs.Document> input = spanTag(span, "_ml_obs_tag.input");
    assertEquals(1, input.size());
    assertEquals("hello world", input.get(0).getText());
    assertEquals(map("encoding_format", "base64"), span.getTag("_ml_obs_tag.metadata"));
  }
}
