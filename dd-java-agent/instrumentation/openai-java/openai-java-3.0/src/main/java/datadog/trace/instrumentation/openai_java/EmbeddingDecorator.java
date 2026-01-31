package datadog.trace.instrumentation.openai_java;

import com.openai.models.embeddings.CreateEmbeddingResponse;
import com.openai.models.embeddings.Embedding;
import com.openai.models.embeddings.EmbeddingCreateParams;
import datadog.trace.api.Config;
import datadog.trace.api.llmobs.LLMObs;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

public class EmbeddingDecorator {
  public static final EmbeddingDecorator DECORATE = new EmbeddingDecorator();

  private static final CharSequence EMBEDDINGS_CREATE = UTF8BytesString.create("createEmbedding");

  private final boolean llmObsEnabled = Config.get().isLlmObsEnabled();

  public void withEmbeddingCreateParams(AgentSpan span, EmbeddingCreateParams params) {
    span.setResourceName(EMBEDDINGS_CREATE);
    span.setTag(CommonTags.OPENAI_REQUEST_ENDPOINT, "/v1/embeddings");
    if (!llmObsEnabled) {
      return;
    }

    span.setTag(CommonTags.SPAN_KIND, Tags.LLMOBS_EMBEDDING_SPAN_KIND);
    if (params == null) {
      return;
    }
    params
        .model()
        ._value()
        .asString()
        .ifPresent(str -> span.setTag(CommonTags.OPENAI_REQUEST_MODEL, str));

    span.setTag(CommonTags.INPUT, embeddingDocuments(params.input()));

    Map<String, Object> metadata = new HashMap<>();
    Optional<String> encodingFormat = params.encodingFormat().flatMap(v -> v._value().asString());
    encodingFormat.ifPresent(v -> metadata.put("encoding_format", v));
    params.dimensions().ifPresent(v -> metadata.put("dimensions", v));
    span.setTag(CommonTags.METADATA, metadata);
  }

  private List<LLMObs.Document> embeddingDocuments(EmbeddingCreateParams.Input input) {
    List<String> inputs = Collections.emptyList();
    if (input.isString()) {
      inputs = Collections.singletonList(input.asString());
    } else if (input.isArrayOfStrings()) {
      inputs = input.asArrayOfStrings();
    }
    return inputs.stream().map(LLMObs.Document::from).collect(Collectors.toList());
  }

  public void withCreateEmbeddingResponse(AgentSpan span, CreateEmbeddingResponse response) {
    if (!llmObsEnabled) {
      return;
    }

    String modelName = response.model();
    span.setTag(CommonTags.OPENAI_RESPONSE_MODEL, modelName);
    span.setTag(CommonTags.MODEL_NAME, modelName);

    if (!response.data().isEmpty()) {
      int embeddingCount = response.data().size();
      Embedding firstEmbedding = response.data().get(0);
      int embeddingSize = firstEmbedding.embedding().size();
      span.setTag(
          CommonTags.OUTPUT,
          String.format("[%d embedding(s) returned with size %d]", embeddingCount, embeddingSize));
    }

    CreateEmbeddingResponse.Usage usage = response.usage();
    span.setTag(CommonTags.INPUT_TOKENS, usage.promptTokens());
    span.setTag(CommonTags.TOTAL_TOKENS, usage.totalTokens());
  }
}
