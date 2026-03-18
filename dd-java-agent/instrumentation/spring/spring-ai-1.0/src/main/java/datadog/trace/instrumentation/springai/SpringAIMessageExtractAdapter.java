package datadog.trace.instrumentation.springai;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

final class SpringAIMessageExtractAdapter {
  private static final int MAX_TAG_VALUE_LENGTH = 8192;

  private SpringAIMessageExtractAdapter() {}

  static String extractPrompt(final Object prompt) {
    if (prompt == null) {
      return null;
    }
    final Object instructions = invokeNoArg(prompt, "getInstructions");
    if (instructions instanceof Iterable) {
      final String joined = joinMessageTexts((Iterable<?>) instructions);
      if (joined != null) {
        return joined;
      }
    }
    final String fallback = prompt.toString();
    return normalize(fallback);
  }

  static String extractOutput(final Object response) {
    if (response == null) {
      return null;
    }
    final Object result = invokeNoArg(response, "getResult");
    final Object output = invokeNoArg(result, "getOutput");
    final String text = asText(output);
    if (text != null) {
      return text;
    }
    final String fallback = response.toString();
    return normalize(fallback);
  }

  static String extractModel(final Object response) {
    if (response == null) {
      return null;
    }
    final Object metadata = invokeNoArg(response, "getMetadata");
    final String modelFromResponse = asText(invokeNoArg(metadata, "getModel"));
    if (modelFromResponse != null) {
      return modelFromResponse;
    }
    final Object result = invokeNoArg(response, "getResult");
    final Object output = invokeNoArg(result, "getOutput");
    final Object outputMetadata = invokeNoArg(output, "getMetadata");
    return asText(invokeNoArg(outputMetadata, "getModel"));
  }

  static Long extractInputTokens(final Object response) {
    final Object usage = extractUsage(response);
    Long tokens = asLong(invokeNoArg(usage, "getInputTokens"));
    if (tokens != null) {
      return tokens;
    }
    tokens = asLong(invokeNoArg(usage, "getPromptTokens"));
    if (tokens != null) {
      return tokens;
    }
    return fromMap(usage, "inputTokens", "input_tokens", "promptTokens", "prompt_tokens");
  }

  static Long extractOutputTokens(final Object response) {
    final Object usage = extractUsage(response);
    Long tokens = asLong(invokeNoArg(usage, "getOutputTokens"));
    if (tokens != null) {
      return tokens;
    }
    tokens = asLong(invokeNoArg(usage, "getGenerationTokens"));
    if (tokens != null) {
      return tokens;
    }
    tokens = asLong(invokeNoArg(usage, "getCompletionTokens"));
    if (tokens != null) {
      return tokens;
    }
    return fromMap(
        usage,
        "outputTokens",
        "output_tokens",
        "generationTokens",
        "generation_tokens",
        "completionTokens",
        "completion_tokens");
  }

  static Long extractTotalTokens(final Object response) {
    final Object usage = extractUsage(response);
    Long tokens = asLong(invokeNoArg(usage, "getTotalTokens"));
    if (tokens != null) {
      return tokens;
    }
    tokens = fromMap(usage, "totalTokens", "total_tokens");
    if (tokens != null) {
      return tokens;
    }

    final Long inputTokens = extractInputTokens(response);
    final Long outputTokens = extractOutputTokens(response);
    if (inputTokens != null && outputTokens != null) {
      return inputTokens + outputTokens;
    }
    return null;
  }

  private static Object extractUsage(final Object response) {
    if (response == null) {
      return null;
    }

    final Object responseMetadata = invokeNoArg(response, "getMetadata");
    final Object usageFromResponseMetadata = invokeNoArg(responseMetadata, "getUsage");
    if (usageFromResponseMetadata != null) {
      return usageFromResponseMetadata;
    }

    final Object result = invokeNoArg(response, "getResult");
    final Object resultMetadata = invokeNoArg(result, "getMetadata");
    final Object usageFromResultMetadata = invokeNoArg(resultMetadata, "getUsage");
    if (usageFromResultMetadata != null) {
      return usageFromResultMetadata;
    }

    final Object output = invokeNoArg(result, "getOutput");
    final Object outputMetadata = invokeNoArg(output, "getMetadata");
    return invokeNoArg(outputMetadata, "getUsage");
  }

  private static String joinMessageTexts(final Iterable<?> messages) {
    final List<String> text = new ArrayList<>();
    for (Object message : messages) {
      final String line = asText(message);
      if (line != null) {
        text.add(line);
      }
    }
    if (text.isEmpty()) {
      return null;
    }
    return normalize(String.join("\n", text));
  }

  private static String asText(final Object value) {
    if (value == null) {
      return null;
    }
    if (value instanceof CharSequence) {
      return normalize(value.toString());
    }
    final Object text = invokeNoArg(value, "getText");
    if (text instanceof CharSequence) {
      return normalize(text.toString());
    }
    return null;
  }

  private static Object invokeNoArg(final Object target, final String methodName) {
    if (target == null) {
      return null;
    }
    try {
      final Method method = target.getClass().getMethod(methodName);
      return method.invoke(target);
    } catch (Throwable ignored) {
      return null;
    }
  }

  private static Long fromMap(final Object candidate, final String... keys) {
    if (!(candidate instanceof Map)) {
      return null;
    }
    final Map<?, ?> map = (Map<?, ?>) candidate;
    for (String key : keys) {
      if (map.containsKey(key)) {
        final Long value = asLong(map.get(key));
        if (value != null) {
          return value;
        }
      }
    }
    return null;
  }

  private static Long asLong(final Object value) {
    if (value instanceof Number) {
      return ((Number) value).longValue();
    }
    if (value instanceof CharSequence) {
      try {
        return Long.parseLong(value.toString());
      } catch (NumberFormatException ignored) {
        return null;
      }
    }
    return null;
  }

  private static String normalize(final String value) {
    if (value == null || value.isEmpty()) {
      return null;
    }
    if (value.length() <= MAX_TAG_VALUE_LENGTH) {
      return value;
    }
    return value.substring(0, MAX_TAG_VALUE_LENGTH);
  }
}
