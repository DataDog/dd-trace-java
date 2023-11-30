package datadog.trace.instrumentation.undertow;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.api.gateway.Events.EVENTS;
import static io.undertow.server.handlers.form.FormDataParser.FORM_DATA;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.returns;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.appsec.api.blocking.BlockingException;
import datadog.trace.advice.ActiveRequestContext;
import datadog.trace.advice.RequiresRequestContext;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.muzzle.Reference;
import datadog.trace.api.gateway.BlockResponseFunction;
import datadog.trace.api.gateway.CallbackProvider;
import datadog.trace.api.gateway.Flow;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.api.gateway.RequestContextSlot;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import io.undertow.server.HttpServerExchange;
import io.undertow.server.handlers.form.FormData;
import java.util.function.BiFunction;
import net.bytebuddy.asm.Advice;

@AutoService(Instrumenter.class)
public class MultiPartUploadHandlerInstrumentation extends Instrumenter.AppSec
    implements Instrumenter.ForSingleType {

  public MultiPartUploadHandlerInstrumentation() {
    super("undertow", "undertow-2.0");
  }

  @Override
  public String instrumentedType() {
    return "io.undertow.server.handlers.form.MultiPartParserDefinition$MultiPartUploadHandler";
  }

  private static final Reference EXCHANGE_REFERENCE =
      new Reference.Builder(
              "io.undertow.server.handlers.form.MultiPartParserDefinition$MultiPartUploadHandler")
          .withField(new String[0], 0, "exchange", "Lio/undertow/server/HttpServerExchange;")
          .build();

  @Override
  public Reference[] additionalMuzzleReferences() {
    return new Reference[] {EXCHANGE_REFERENCE};
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {packageName + ".FormDataMap"};
  }

  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        named("parseBlocking")
            .and(takesArguments(0))
            .and(returns(named("io.undertow.server.handlers.form.FormData")))
            .and(isPublic()),
        getClass().getName() + "$ParseBlockingAdvice");
  }

  @RequiresRequestContext(RequestContextSlot.APPSEC)
  public static class ParseBlockingAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    static boolean onEnter(@Advice.FieldValue("exchange") HttpServerExchange exchange) {
      return exchange.getAttachment(FORM_DATA) == null;
    }

    @Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class)
    static void after(
        @Advice.Enter boolean relevant,
        @Advice.FieldValue("exchange") HttpServerExchange exchange,
        @ActiveRequestContext RequestContext reqCtx,
        @Advice.Thrown(readOnly = false) Throwable t) {
      if (!relevant || t != null) {
        return;
      }

      CallbackProvider cbp = AgentTracer.get().getCallbackProvider(RequestContextSlot.APPSEC);
      BiFunction<RequestContext, Object, Flow<Void>> callback =
          cbp.getCallback(EVENTS.requestBodyProcessed());
      if (callback == null) {
        return;
      }
      FormData attachment = exchange.getAttachment(FORM_DATA);
      if (attachment == null) {
        return;
      }

      Flow<Void> flow = callback.apply(reqCtx, new FormDataMap(attachment));
      Flow.Action action = flow.getAction();
      if (action instanceof Flow.Action.RequestBlockingAction) {
        Flow.Action.RequestBlockingAction rba = (Flow.Action.RequestBlockingAction) action;
        BlockResponseFunction blockResponseFunction = reqCtx.getBlockResponseFunction();
        if (blockResponseFunction != null) {
          blockResponseFunction.tryCommitBlockingResponse(
              reqCtx.getTraceSegment(),
              rba.getStatusCode(),
              rba.getBlockingContentType(),
              rba.getExtraHeaders());
          if (t == null) {
            t = new BlockingException("Blocked request (for MultiPartUploadHandler/parseBlocking)");
          }
        }
      }
    }
  }
}
