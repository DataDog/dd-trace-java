package datadog.trace.instrumentation.openai_java;

import static datadog.trace.instrumentation.openai_java.OpenAiDecorator.REQUEST_MODEL;
import static datadog.trace.instrumentation.openai_java.OpenAiDecorator.RESPONSE_MODEL;

import com.openai.models.embeddings.CreateEmbeddingResponse;
import com.openai.models.embeddings.Embedding;
import com.openai.models.embeddings.EmbeddingCreateParams;
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

  public void withEmbeddingCreateParams(AgentSpan span, EmbeddingCreateParams params) {
    span.setTag("_ml_obs_tag.span.kind", Tags.LLMOBS_EMBEDDING_SPAN_KIND);
    span.setResourceName(EMBEDDINGS_CREATE);
    span.setTag("openai.request.endpoint", "v1/embeddings");
    span.setTag("openai.request.method", "POST");
    if (params == null) {
      return;
    }
    params.model()._value().asString().ifPresent(str -> span.setTag(REQUEST_MODEL, str));

    span.setTag("_ml_obs_tag.input", embeddingDocuments(params.input()));

    Map<String, Object> metadata = new HashMap<>();
    Optional<String> encodingFormat = params.encodingFormat().flatMap(v -> v._value().asString());
    encodingFormat.ifPresent(v -> metadata.put("encoding_format", v));
    params.dimensions().ifPresent(v -> metadata.put("dimensions", v));
    span.setTag("_ml_obs_tag.metadata", metadata);
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
    String modelName = response.model();
    span.setTag(RESPONSE_MODEL, modelName);
    span.setTag("_ml_obs_tag.model_name", modelName);
    span.setTag("_ml_obs_tag.model_provider", "openai");

    if (!response.data().isEmpty()) {
      int embeddingCount = response.data().size();
      Embedding firstEmbedding = response.data().get(0);
      int embeddingSize = firstEmbedding.embedding().size();
      span.setTag(
          "_ml_obs_tag.output",
          String.format("[%d embedding(s) returned with size %d]", embeddingCount, embeddingSize));
    }
  }
}
