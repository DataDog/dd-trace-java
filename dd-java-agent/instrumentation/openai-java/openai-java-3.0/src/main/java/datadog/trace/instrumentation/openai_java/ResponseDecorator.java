package datadog.trace.instrumentation.openai_java;

import static datadog.trace.instrumentation.openai_java.OpenAiDecorator.REQUEST_MODEL;
import static datadog.trace.instrumentation.openai_java.OpenAiDecorator.RESPONSE_MODEL;

import com.openai.core.JsonField;
import com.openai.models.Reasoning;
import com.openai.models.ResponsesModel;
import com.openai.models.responses.Response;
import com.openai.models.responses.ResponseCreateParams;
import com.openai.models.responses.ResponseFunctionToolCall;
import com.openai.models.responses.ResponseOutputItem;
import com.openai.models.responses.ResponseOutputMessage;
import com.openai.models.responses.ResponseOutputText;
import com.openai.models.responses.ResponseReasoningItem;
import com.openai.models.responses.ResponseStreamEvent;
import datadog.json.JsonWriter;
import datadog.trace.api.llmobs.LLMObs;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class ResponseDecorator {
  public static final ResponseDecorator DECORATE = new ResponseDecorator();

  private static final CharSequence RESPONSES_CREATE = UTF8BytesString.create("createResponse");

  public void withResponseCreateParams(AgentSpan span, ResponseCreateParams params) {
    span.setTag("_ml_obs_tag.span.kind", Tags.LLMOBS_LLM_SPAN_KIND);
    span.setResourceName(RESPONSES_CREATE);
    span.setTag("openai.request.endpoint", "v1/responses");
    span.setTag("openai.request.method", "POST");
    if (params == null) {
      return;
    }
    // Use ResponseCreateParams._model() b/o ResponseCreateParams.model() changed type from
    // ResponsesModel to Optional<ResponsesModel> in
    // https://github.com/openai/openai-java/commit/87dd64658da6cec7564f3b571e15ec0e2db0660b
    String modelName = extractResponseModel(params._model());
    span.setTag(REQUEST_MODEL, modelName);

    List<LLMObs.LLMMessage> inputMessages = new ArrayList<>();

    params
        .instructions()
        .ifPresent(
            instructions -> {
              inputMessages.add(LLMObs.LLMMessage.from("system", instructions));
            });

    Optional<String> textOpt = params._input().asString();
    if (textOpt.isPresent()) {
      inputMessages.add(LLMObs.LLMMessage.from("user", textOpt.get()));
    }

    if (!inputMessages.isEmpty()) {
      span.setTag("_ml_obs_tag.input", inputMessages);
    }

    extractReasoningFromParams(params)
        .ifPresent(reasoningMap -> span.setTag("_ml_obs_request.reasoning", reasoningMap));
  }

  private Optional<Map<String, String>> extractReasoningFromParams(ResponseCreateParams params) {
    com.openai.core.JsonField<Reasoning> reasoningField = params._reasoning();
    if (reasoningField.isMissing()) {
      return Optional.empty();
    }

    Map<String, String> reasoningMap = new HashMap<>();

    Optional<Reasoning> knownReasoning = reasoningField.asKnown();
    if (knownReasoning.isPresent()) {
      Reasoning reasoning = knownReasoning.get();
      reasoning.effort().ifPresent(effort -> reasoningMap.put("effort", effort.asString()));
      reasoning.summary().ifPresent(summary -> reasoningMap.put("summary", summary.asString()));
    } else {
      Optional<Map<String, com.openai.core.JsonValue>> rawObject = reasoningField.asObject();
      if (rawObject.isPresent()) {
        Map<String, com.openai.core.JsonValue> obj = rawObject.get();
        com.openai.core.JsonValue effortVal = obj.get("effort");
        if (effortVal != null) {
          effortVal.asString().ifPresent(v -> reasoningMap.put("effort", String.valueOf(v)));
        }
        com.openai.core.JsonValue summaryVal = obj.get("summary");
        if (summaryVal == null) {
          summaryVal = obj.get("generate_summary");
        }
        if (summaryVal != null) {
          summaryVal.asString().ifPresent(v -> reasoningMap.put("summary", String.valueOf(v)));
        }
      }
    }

    return reasoningMap.isEmpty() ? Optional.empty() : Optional.of(reasoningMap);
  }

  public void withResponse(AgentSpan span, Response response) {
    withResponse(span, response, false);
  }

  public void withResponseStreamEvents(AgentSpan span, List<ResponseStreamEvent> events) {
    for (ResponseStreamEvent event : events) {
      if (event.isCompleted()) {
        Response response = event.asCompleted().response();
        withResponse(span, response, true);
        return;
      }
      if (event.isIncomplete()) {
        Response response = event.asIncomplete().response();
        withResponse(span, response, true);
        return;
      }
    }
  }

  private void withResponse(AgentSpan span, Response response, boolean stream) {
    String modelName = extractResponseModel(response._model());
    span.setTag(RESPONSE_MODEL, modelName);
    span.setTag("_ml_obs_tag.model_name", modelName);
    span.setTag("_ml_obs_tag.model_provider", "openai");

    List<LLMObs.LLMMessage> outputMessages = extractResponseOutputMessages(response.output());
    if (!outputMessages.isEmpty()) {
      span.setTag("_ml_obs_tag.output", outputMessages);
    }

    Map<String, Object> metadata = new HashMap<>();

    Object reasoningTag = span.getTag("_ml_obs_request.reasoning");
    if (reasoningTag != null) {
      metadata.put("reasoning", reasoningTag);
    }

    response.maxOutputTokens().ifPresent(v -> metadata.put("max_output_tokens", v));
    response.temperature().ifPresent(v -> metadata.put("temperature", v));
    response.topP().ifPresent(v -> metadata.put("top_p", v));

    Response.ToolChoice toolChoice = response.toolChoice();
    if (toolChoice.isOptions()) {
      metadata.put("tool_choice", toolChoice.asOptions()._value().asString().orElse(null));
    } else if (toolChoice.isTypes()) {
      metadata.put("tool_choice", toolChoice.asTypes().type().toString().toLowerCase());
    } else if (toolChoice.isFunction()) {
      metadata.put("tool_choice", "function");
    }

    response
        .truncation()
        .ifPresent(
            (Response.Truncation t) ->
                metadata.put("truncation", t._value().asString().orElse(null)));

    response
        .text()
        .ifPresent(
            textConfig -> {
              textConfig
                  .format()
                  .ifPresent(
                      format -> {
                        Map<String, Object> textMap = new HashMap<>();
                        Map<String, String> formatMap = new HashMap<>();
                        if (format.isText()) {
                          formatMap.put("type", "text");
                          // metadata.put("text.format.type", "text");
                          // } else if (format.isJsonSchema()) {
                          //   formatMap.put("type", "json_schema");
                          // } else if (format.isJsonObject()) {
                          //   formatMap.put("type", "json_object");
                        }
                        textMap.put("format", formatMap);
                        metadata.put("text", textMap);
                      });
            });

    if (stream) {
      metadata.put("stream", true);
    }

    response
        .usage()
        .ifPresent(
            usage -> {
              span.setTag("_ml_obs_metric.input_tokens", usage.inputTokens());
              span.setTag("_ml_obs_metric.output_tokens", usage.outputTokens());
              span.setTag("_ml_obs_metric.total_tokens", usage.totalTokens());
              span.setTag(
                  "_ml_obs_metric.cache_read_input_tokens",
                  usage.inputTokensDetails().cachedTokens());
              long reasoningTokens = usage.outputTokensDetails().reasoningTokens();
              metadata.put("reasoning_tokens", reasoningTokens);
            });

    span.setTag("_ml_obs_tag.metadata", metadata);
  }

  private List<LLMObs.LLMMessage> extractResponseOutputMessages(List<ResponseOutputItem> output) {
    List<LLMObs.LLMMessage> messages = new ArrayList<>();

    for (ResponseOutputItem item : output) {
      if (item.isFunctionCall()) {
        ResponseFunctionToolCall functionCall = item.asFunctionCall();
        LLMObs.ToolCall toolCall = ToolCallExtractor.getToolCall(functionCall);
        if (toolCall != null) {
          List<LLMObs.ToolCall> toolCalls = Collections.singletonList(toolCall);
          messages.add(LLMObs.LLMMessage.from("assistant", null, toolCalls));
        }
      } else if (item.isMessage()) {
        ResponseOutputMessage message = item.asMessage();
        String textContent = extractMessageContent(message);
        Optional<String> roleOpt = message._role().asString();
        String role = roleOpt.orElse("assistant");
        messages.add(LLMObs.LLMMessage.from(role, textContent));
      } else if (item.isReasoning()) {
        ResponseReasoningItem reasoning = item.asReasoning();
        try (JsonWriter writer = new JsonWriter()) {
          writer.beginObject();
          if (!reasoning.summary().isEmpty()) {
            writer.name("summary").value(reasoning.summary().get(0).text());
          }
          reasoning.encryptedContent().ifPresent(v -> writer.name("encrypted_content").value(v));
          writer.name("id").value(reasoning.id());
          writer.endObject();
          messages.add(LLMObs.LLMMessage.from("reasoning", writer.toString()));
        }
      }
    }
    return messages;
  }

  private String extractMessageContent(ResponseOutputMessage message) {
    StringBuilder contentBuilder = new StringBuilder();
    for (ResponseOutputMessage.Content content : message.content()) {
      if (content.isOutputText()) {
        ResponseOutputText outputText = content.asOutputText();
        contentBuilder.append(outputText.text());
      }
    }
    String result = contentBuilder.toString();
    return result.isEmpty() ? null : result;
  }

  private String extractResponseModel(JsonField<ResponsesModel> model) {
    Optional<String> str = model.asString();
    if (str.isPresent()) {
      return str.get();
    }
    Optional<ResponsesModel> known = model.asKnown();
    if (known.isPresent()) {
      ResponsesModel m = known.get();
      if (m.isString()) {
        return m.asString();
      }
      if (m.isChat()) {
        Optional<String> s = m.asChat()._value().asString();
        if (s.isPresent()) {
          return s.get();
        }
      }
      if (m.isOnly()) {
        Optional<String> s = m.asOnly()._value().asString();
        if (s.isPresent()) {
          return s.get();
        }
      }
    }
    return null;
  }
}
