package datadog.trace.instrumentation.undertow;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.api.gateway.Events.EVENTS;
import static io.undertow.server.handlers.form.FormDataParser.FORM_DATA;
import static net.bytebuddy.matcher.ElementMatchers.isPrivate;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.appsec.api.blocking.BlockingException;
import datadog.trace.advice.ActiveRequestContext;
import datadog.trace.advice.RequiresRequestContext;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
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

@AutoService(InstrumenterModule.class)
public class FormDataParserInstrumentation extends InstrumenterModule.AppSec
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

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

  private static final Reference EXCHANGE_REFERENCE =
      new Reference.Builder(
              "io.undertow.server.handlers.form.FormEncodedDataDefinition$FormEncodedDataParser")
          .withField(new String[0], 0, "exchange", "Lio/undertow/server/HttpServerExchange;")
          .build();

  @Override
  public Reference[] additionalMuzzleReferences() {
    return new Reference[] {EXCHANGE_REFERENCE};
  }

  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        named("doParse")
            .and(takesArgument(0, named("org.xnio.channels.StreamSourceChannel")))
            .and(takesArguments(1))
            .and(isPrivate()),
        getClass().getName() + "$DoParseAdvice");
  }

  @RequiresRequestContext(RequestContextSlot.APPSEC)
  public static class DoParseAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class)
    static void after(
        @Advice.FieldValue("exchange") HttpServerExchange exchange,
        @ActiveRequestContext RequestContext reqCtx,
        @Advice.Thrown(readOnly = false) Throwable t) {
      if (t != null) {
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
          blockResponseFunction.tryCommitBlockingResponse(reqCtx.getTraceSegment(), rba);
          if (t == null) {
            t = new BlockingException("Blocked request (for FormEncodedDataParser/doParse)");
          }
        }
      }
    }
  }
}
