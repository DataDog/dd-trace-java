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
    if (params == null) {
      return;
    }
    Optional<String> modelName = extractEmbeddingModelName(params);
    modelName.ifPresent(str -> span.setTag(CommonTags.OPENAI_REQUEST_MODEL, str));

    if (!llmObsEnabled) {
      return;
    }

    // Keep model_name stable on error paths where no response is available.
    modelName.ifPresent(str -> span.setTag(CommonTags.MODEL_NAME, str));

    span.setTag(CommonTags.SPAN_KIND, Tags.LLMOBS_EMBEDDING_SPAN_KIND);

    span.setTag(CommonTags.INPUT, embeddingDocuments(params.input()));

    Map<String, Object> metadata = new HashMap<>();
    Optional<String> encodingFormat = extractEncodingFormat(params);
    encodingFormat.ifPresent(v -> metadata.put("encoding_format", v));
    params.dimensions().ifPresent(v -> metadata.put("dimensions", v));
    span.setTag(CommonTags.METADATA, metadata);
  }

  private List<LLMObs.Document> embeddingDocuments(EmbeddingCreateParams.Input input) {
    List<String> inputs =
        input
            .string()
            .map(Collections::singletonList)
            .orElseGet(() -> input.arrayOfStrings().orElse(Collections.emptyList()));
    return inputs.stream().map(LLMObs.Document::from).collect(Collectors.toList());
  }

  public void withCreateEmbeddingResponse(AgentSpan span, CreateEmbeddingResponse response) {
    String modelName = response._model().asString().orElse(null);
    span.setTag(CommonTags.OPENAI_RESPONSE_MODEL, modelName);
    span.setTag(CommonTags.MODEL_NAME, modelName);

    if (!llmObsEnabled) {
      return;
    }

    List<Embedding> data = response._data().asKnown().orElse(Collections.emptyList());
    if (!data.isEmpty()) {
      int embeddingCount = data.size();
      Embedding firstEmbedding = data.get(0);
      int embeddingSize =
          firstEmbedding._embedding().asKnown().orElse(Collections.emptyList()).size();
      span.setTag(
          CommonTags.OUTPUT,
          String.format("[%d embedding(s) returned with size %d]", embeddingCount, embeddingSize));
    }

    response
        ._usage()
        .asKnown()
        .ifPresent(
            usage -> {
              usage
                  ._promptTokens()
                  .asKnown()
                  .ifPresent(v -> span.setTag(CommonTags.INPUT_TOKENS, v));
              usage
                  ._totalTokens()
                  .asKnown()
                  .ifPresent(v -> span.setTag(CommonTags.TOTAL_TOKENS, v));
            });
  }

  private Optional<String> extractEmbeddingModelName(EmbeddingCreateParams params) {
    Optional<String> modelName =
        params._model().asKnown().flatMap(model -> model._value().asString());
    return modelName.isPresent() ? modelName : params._model().asString();
  }

  private Optional<String> extractEncodingFormat(EmbeddingCreateParams params) {
    Optional<String> encodingFormat =
        params._encodingFormat().asKnown().flatMap(format -> format._value().asString());
    return encodingFormat.isPresent() ? encodingFormat : params._encodingFormat().asString();
  }
}
