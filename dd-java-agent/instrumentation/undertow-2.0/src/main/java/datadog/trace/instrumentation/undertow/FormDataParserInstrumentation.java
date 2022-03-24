package datadog.trace.instrumentation.undertow;

import static datadog.trace.api.gateway.Events.EVENTS;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;
import static io.undertow.server.handlers.form.FormDataParser.FORM_DATA;
import static net.bytebuddy.matcher.ElementMatchers.*;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.muzzle.IReferenceMatcher;
import datadog.trace.agent.tooling.muzzle.Reference;
import datadog.trace.agent.tooling.muzzle.ReferenceMatcher;
import datadog.trace.api.function.BiFunction;
import datadog.trace.api.gateway.CallbackProvider;
import datadog.trace.api.gateway.Flow;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import io.undertow.server.HttpServerExchange;
import io.undertow.server.handlers.form.FormData;
import net.bytebuddy.asm.Advice;

@AutoService(Instrumenter.class)
public class FormDataParserInstrumentation extends Instrumenter.AppSec
    implements Instrumenter.ForSingleType {

  public FormDataParserInstrumentation() {
    super("undertow", "undertow-2.0");
  }

  @Override
  public String instrumentedType() {
    return "io.undertow.server.handlers.form.FormEncodedDataDefinition$FormEncodedDataParser";
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {packageName + ".FormDataMap"};
  }

  private static final ReferenceMatcher EXCHANGE_REFERENCE_MATCHER =
      new ReferenceMatcher(
          new Reference.Builder(
                  "io.undertow.server.handlers.form.FormEncodedDataDefinition$FormEncodedDataParser")
              .withField(new String[0], 0, "exchange", "Lio/undertow/server/HttpServerExchange;")
              .build());

  private IReferenceMatcher postProcessReferenceMatcher(final ReferenceMatcher origMatcher) {
    return new IReferenceMatcher.ConjunctionReferenceMatcher(
        origMatcher, EXCHANGE_REFERENCE_MATCHER);
  }

  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        named("doParse")
            .and(takesArgument(0, named("org.xnio.channels.StreamSourceChannel")))
            .and(takesArguments(1))
            .and(isPrivate()),
        getClass().getName() + "$DoParseAdvice");
  }

  public static class DoParseAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    static void after(@Advice.FieldValue("exchange") HttpServerExchange exchange) {
      AgentSpan agentSpan = activeSpan();
      if (agentSpan == null) {
        return;
      }

      CallbackProvider cbp = AgentTracer.get().instrumentationGateway();
      BiFunction<RequestContext<Object>, Object, Flow<Void>> callback =
          cbp.getCallback(EVENTS.requestBodyProcessed());
      RequestContext<Object> requestContext = agentSpan.getRequestContext();
      if (requestContext == null || callback == null) {
        return;
      }
      FormData attachment = exchange.getAttachment(FORM_DATA);
      if (attachment == null) {
        return;
      }

      callback.apply(requestContext, new FormDataMap(attachment));
    }
  }
}
