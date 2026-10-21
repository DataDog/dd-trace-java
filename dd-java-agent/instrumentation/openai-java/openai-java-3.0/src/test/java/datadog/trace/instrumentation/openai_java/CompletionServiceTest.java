package datadog.trace.instrumentation.openai_java;

import static datadog.trace.agent.test.utils.TraceUtils.runUnderTrace;
import static datadog.trace.agent.test.utils.TraceUtils.runnableUnderTrace;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.openai.core.http.AsyncStreamResponse;
import com.openai.core.http.HttpResponseFor;
import com.openai.core.http.StreamResponse;
import com.openai.models.completions.Completion;
import com.openai.models.completions.CompletionCreateParams;
import datadog.trace.api.llmobs.LLMObs;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.core.DDSpan;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;
import org.tabletest.junit.TableTest;

class CompletionServiceTest extends OpenAiTest {
  @TableTest({
    "scenario | json ",
    "typed    | false",
    "JSON     | true "
  })
  void createCompletionTest(boolean json) {
    CompletionCreateParams params = completionCreateParams(json);

    runUnderTrace("parent", () -> openAiClient.completions().create(params));

    assertCompletionTrace(false);
  }

  @TableTest({
    "scenario | json ",
    "typed    | false",
    "JSON     | true "
  })
  void createCompletionTestWithRawResponse(boolean json) {
    CompletionCreateParams params = completionCreateParams(json);

    try (HttpResponseFor<Completion> resp =
        runUnderTrace(
            "parent", () -> openAiClient.withRawResponse().completions().create(params))) {

      assertEquals(200, resp.statusCode());
      assertTrue(resp.parse().isValid()); // force response parsing, so it sets all the tags
      assertCompletionTrace(false);
    }
  }

  @TableTest({
    "scenario | json ",
    "typed    | false",
    "JSON     | true "
  })
  void createStreamingCompletionTest(boolean json) {
    CompletionCreateParams params = completionCreateStreamedParams(json);

    runnableUnderTrace(
        "parent",
        () -> {
          try (StreamResponse<Completion> streamCompletion =
                  openAiClient.completions().createStreaming(params);
              Stream<?> stream = streamCompletion.stream()) {
            stream.forEach(
                chunk -> {
                  // consume the stream
                });
          }
        });

    assertCompletionTrace(true);
  }

  @TableTest({
    "scenario | json ",
    "typed    | false",
    "JSON     | true "
  })
  void createStreamingCompletionTestWithRawResponse(boolean json) {
    CompletionCreateParams params = completionCreateStreamedParams(json);

    runnableUnderTrace(
        "parent",
        () -> {
          try (HttpResponseFor<StreamResponse<Completion>> streamCompletion =
                  openAiClient.completions().withRawResponse().createStreaming(params);
              Stream<?> stream = streamCompletion.parse().stream()) {
            stream.forEach(
                chunk -> {
                  // consume the stream
                });
          }
        });

    assertCompletionTrace(true);
  }

  @TableTest({
    "scenario | json ",
    "typed    | false",
    "JSON     | true "
  })
  void createAsyncCompletionTest(boolean json) throws Exception {
    CompletionCreateParams params = completionCreateParams(json);

    CompletableFuture<Completion> completionFuture =
        runUnderTrace("parent", () -> openAiClient.async().completions().create(params));

    completionFuture.get();

    assertCompletionTrace(false);
  }

  @TableTest({
    "scenario | json ",
    "typed    | false",
    "JSON     | true "
  })
  void createAsyncCompletionTestWithRawResponse(boolean json) throws Exception {
    CompletionCreateParams params = completionCreateParams(json);

    CompletableFuture<HttpResponseFor<Completion>> completionFuture =
        runUnderTrace(
            "parent", () -> openAiClient.async().completions().withRawResponse().create(params));

    try (HttpResponseFor<Completion> resp = completionFuture.get()) {
      resp.parse().isValid(); // force response parsing, so it sets all the tags

      assertCompletionTrace(false);
    }
  }

  @TableTest({
    "scenario | json ",
    "typed    | false",
    "JSON     | true "
  })
  void createStreamingAsyncCompletionTest(boolean json) throws Exception {
    CompletionCreateParams params = completionCreateStreamedParams(json);

    AsyncStreamResponse<Completion> asyncResp =
        runUnderTrace("parent", () -> openAiClient.async().completions().createStreaming(params));
    asyncResp.subscribe(
        chunk -> {
          // consume completions
        });
    asyncResp.onCompleteFuture().get();
    assertCompletionTrace(true);
  }

  @TableTest({
    "scenario | json ",
    "typed    | false",
    "JSON     | true "
  })
  void createStreamingAsyncCompletionTestWithRawResponse(boolean json) throws Exception {
    CompletionCreateParams params = completionCreateStreamedParams(json);

    CompletableFuture<HttpResponseFor<StreamResponse<Completion>>> future =
        runUnderTrace(
            "parent",
            () -> openAiClient.async().completions().withRawResponse().createStreaming(params));
    try (HttpResponseFor<StreamResponse<Completion>> resp = future.get()) {
      try (Stream<?> stream = resp.parse().stream()) {
        stream.forEach(
            chunk -> {
              // consume the stream
            });
      }
      assertEquals(200, resp.statusCode());
      assertCompletionTrace(true);
    }
  }

  private void assertCompletionTrace(boolean streamRequest) {
    Map<String, Object> tags = new HashMap<>();
    tags.put("_ml_obs_tag.span.kind", "llm");
    tags.put("_ml_obs_tag.model_provider", "openai");
    tags.put("_ml_obs_tag.model_name", String.class);
    tags.put("_ml_obs_tag.metadata", Map.class);
    tags.put("_ml_obs_tag.input", List.class);
    tags.put("_ml_obs_tag.output", List.class);
    tags.put("_ml_obs_metric.input_tokens", Long.class);
    tags.put("_ml_obs_metric.output_tokens", Long.class);
    tags.put("_ml_obs_metric.total_tokens", Long.class);
    tags.put("_ml_obs_tag.parent_id", "undefined");
    tags.put("_ml_obs_tag.sampling_decision", "1");
    tags.put("_ml_obs_tag.sample_rate", "1");
    tags.put("_ml_obs_tag.ml_app", String.class);
    tags.put("gen_ai.operation.name", "llm");
    tags.put("gen_ai.request.model", String.class);
    tags.put("gen_ai.provider.name", "openai");
    tags.put("gen_ai.application.name", String.class);
    tags.put("gen_ai.usage.input_tokens", Double.class);
    tags.put("gen_ai.usage.output_tokens", Double.class);
    tags.put("gen_ai.usage.total_tokens", Double.class);
    tags.put("_dd.llmobs.artificial_gen_ai_tags", "true");
    tags.put("_ml_obs_tag.service", String.class);
    tags.put(CommonTags.DDTRACE_VERSION, String.class);
    tags.put(CommonTags.SOURCE, "integration");
    tags.put(CommonTags.INTEGRATION, "openai");
    tags.put(CommonTags.ERROR, 0);
    tags.put("openai.request.method", "POST");
    tags.put("openai.request.endpoint", "/v1/completions");
    tags.put("openai.api_base", openAiBaseApi);
    tags.put("openai.organization.ratelimit.requests.limit", 3500);
    tags.put("openai.organization.ratelimit.requests.remaining", Integer.class);
    tags.put("openai.organization.ratelimit.tokens.limit", 90000);
    tags.put("openai.organization.ratelimit.tokens.remaining", Integer.class);
    tags.put(CommonTags.OPENAI_REQUEST_MODEL, "gpt-3.5-turbo-instruct");
    tags.put(CommonTags.OPENAI_RESPONSE_MODEL, "gpt-3.5-turbo-instruct:20230824-v2");
    tags.put(CommonTags.OPENAI_ORGANIZATION, "datadog-staging");
    tags.put(Tags.COMPONENT, "openai");
    tags.put(Tags.SPAN_KIND, Tags.SPAN_KIND_CLIENT);
    DDSpan span = assertOpenAiTrace("createCompletion", "/v1/completions", tags);
    List<LLMObs.LLMMessage> input = spanTag(span, "_ml_obs_tag.input");
    List<LLMObs.LLMMessage> output = spanTag(span, "_ml_obs_tag.output");
    Map<String, Object> metadata = spanTag(span, "_ml_obs_tag.metadata");
    assertEquals(1, input.size());
    assertEquals("", input.get(0).getRole());
    assertEquals("Tell me a story about building the best SDK!", input.get(0).getContent());
    assertFalse(output.isEmpty());
    for (LLMObs.LLMMessage message : output) {
      assertEquals("", message.getRole());
    }
    if (streamRequest) {
      assertEquals(map("include_usage", true), metadata.get("stream_options"));
    } else {
      assertFalse(metadata.containsKey("stream_options"));
    }
  }
}
