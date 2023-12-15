package datadog.trace.instrumentation.graphqljava;

import static datadog.trace.api.gateway.Events.EVENTS;

import datadog.trace.api.gateway.CallbackProvider;
import datadog.trace.api.gateway.Flow;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.api.gateway.RequestContextSlot;
import datadog.trace.api.naming.SpanNaming;
import datadog.trace.bootstrap.ActiveSubsystems;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.bootstrap.instrumentation.api.InternalSpanTypes;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.bootstrap.instrumentation.decorator.BaseDecorator;
import graphql.execution.ExecutionContext;
import graphql.language.Argument;
import graphql.language.Field;
import graphql.language.Selection;
import graphql.language.StringValue;
import graphql.language.Value;
import java.util.HashMap;
import java.util.Map;
import java.util.function.BiFunction;

public class GraphQLDecorator extends BaseDecorator {
  public static final GraphQLDecorator DECORATE = new GraphQLDecorator();
  public static final CharSequence GRAPHQL_REQUEST =
      UTF8BytesString.create(
          SpanNaming.instance().namingSchema().server().operationForProtocol("graphql"));
  public static final CharSequence GRAPHQL_PARSING = UTF8BytesString.create("graphql.parsing");
  public static final CharSequence GRAPHQL_VALIDATION =
      UTF8BytesString.create("graphql.validation");
  public static final CharSequence GRAPHQL_JAVA = UTF8BytesString.create("graphql-java");

  // Extract this to allow for easier testing
  protected AgentTracer.TracerAPI tracer() {
    return AgentTracer.get();
  }

  @Override
  protected String[] instrumentationNames() {
    return new String[] {"graphql-java"};
  }

  @Override
  protected CharSequence spanType() {
    return InternalSpanTypes.GRAPHQL;
  }

  @Override
  protected CharSequence component() {
    return GRAPHQL_JAVA;
  }

  @Override
  public AgentSpan afterStart(final AgentSpan span) {
    span.setMeasured(true);
    return super.afterStart(span);
  }

  public AgentSpan onRequest(final AgentSpan span, final ExecutionContext context) {

    if (ActiveSubsystems.APPSEC_ACTIVE) {

      Map<String, Map<String, String>> resolversArgs = new HashMap<>();

      for (Selection<?> selection :
          context.getOperationDefinition().getSelectionSet().getSelections()) {
        if (selection instanceof Field) {
          Field field = (Field) selection;
          String name = field.getName();

          Map<String, String> arguments = new HashMap<>();

          for (Argument argument : field.getArguments()) {
            String fieldName = argument.getName();
            Value<?> fieldValue = argument.getValue();
            if (fieldValue instanceof StringValue) {
              String stringValue = ((StringValue) fieldValue).getValue();
              arguments.put(fieldName, stringValue);
            }
          }
          resolversArgs.put(name, arguments);
        }
      }

      CallbackProvider cbp = tracer().getCallbackProvider(RequestContextSlot.APPSEC);
      RequestContext ctx = span.getRequestContext();
      if (cbp == null || resolversArgs.isEmpty() || ctx == null) {
        return null;
      }

      BiFunction<RequestContext, Map<String, ?>, Flow<Void>> graphqlResolverCallback =
          cbp.getCallback(EVENTS.graphqlServerRequestMessage());
      if (graphqlResolverCallback == null) {
        return null;
      }

      Flow<Void> flow = graphqlResolverCallback.apply(ctx, resolversArgs);
      if (flow.getAction() instanceof Flow.Action.RequestBlockingAction) {
        // Blocking will be implemented in future PRs
        // span.setRequestBlockingAction((Flow.Action.RequestBlockingAction) flow.getAction());
      }
    }

    return span;
  }
}
