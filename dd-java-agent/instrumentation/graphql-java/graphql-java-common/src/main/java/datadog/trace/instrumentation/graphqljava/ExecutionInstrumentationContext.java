package datadog.trace.instrumentation.graphqljava;

import static datadog.trace.instrumentation.graphqljava.GraphQLDecorator.DECORATE;

import datadog.trace.api.Config;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.SpanNativeAttributes;
import graphql.ExecutionResult;
import graphql.GraphQLError;
import graphql.execution.instrumentation.SimpleInstrumentationContext;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class ExecutionInstrumentationContext extends SimpleInstrumentationContext<ExecutionResult> {
  private final State state;
  private static final List<String> errorExtensions = Config.get().getTraceGraphqlErrorExtensions();

  public ExecutionInstrumentationContext(State state) {
    this.state = state;
  }

  @Override
  public void onCompleted(ExecutionResult result, Throwable t) {
    List<GraphQLError> errors = result.getErrors();
    AgentSpan requestSpan = state.getRequestSpan();
    if (t != null) {
      DECORATE.onError(requestSpan, t);
    } else {
      int errorCounter = errors.size();
      if (errorCounter >= 1) {
        String error = errors.get(0).getMessage();
        if (errorCounter > 1) {
          error += " (and " + (errorCounter - 1) + " more errors)";
        }
        requestSpan.setErrorMessage(error);
        requestSpan.setError(true);

        // Add span events for each GraphQL error
        for (GraphQLError graphQLError : errors) {
          SpanNativeAttributes.Builder attributes =
              SpanNativeAttributes.builder().put("message", graphQLError.getMessage());

          // Add locations if available
          if (graphQLError.getLocations() != null && !graphQLError.getLocations().isEmpty()) {
            List<String> locationStrings =
                graphQLError.getLocations().stream()
                    .map(loc -> loc.getLine() + ":" + loc.getColumn())
                    .collect(Collectors.toList());
            attributes.putStringArray("locations", locationStrings);
          }

          // Add path if available
          if (graphQLError.getPath() != null && !graphQLError.getPath().isEmpty()) {
            List<String> pathStrings =
                graphQLError.getPath().stream().map(Object::toString).collect(Collectors.toList());
            attributes.putStringArray("path", pathStrings);
          }

          // Add extensions if available
          Map<String, Object> extensions = graphQLError.getExtensions();
          if (extensions != null && !extensions.isEmpty()) {

            for (String extensionKey : errorExtensions) {
              if (extensions.containsKey(extensionKey)) {
                Object value = extensions.get(extensionKey);
                if (value != null) {
                  if (value instanceof Number) {
                    if (value instanceof Long) {
                      attributes.put("extensions." + extensionKey, (Long) value);
                    } else if (value instanceof Double) {
                      attributes.put("extensions." + extensionKey, (Double) value);
                    } else {
                      attributes.put("extensions." + extensionKey, value.toString());
                    }
                  } else if (value instanceof Boolean) {
                    attributes.put("extensions." + extensionKey, (Boolean) value);
                  } else if (value instanceof List) {
                    List<?> list = (List<?>) value;
                    if (!list.isEmpty() && list.get(0) instanceof String) {
                      attributes.putStringArray(
                          "extensions." + extensionKey,
                          list.stream().map(Object::toString).collect(Collectors.toList()));
                    } else {
                      attributes.put("extensions." + extensionKey, value.toString());
                    }
                  } else {
                    attributes.put("extensions." + extensionKey, value.toString());
                  }
                }
              }
            }
          }

          requestSpan.addEvent("dd.graphql.query.error", attributes.build());
        }
      }
    }
    requestSpan.setTag("graphql.source", state.getQuery());
    DECORATE.beforeFinish(requestSpan);
    requestSpan.finish();
  }
}
