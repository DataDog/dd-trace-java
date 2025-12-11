package datadog.trace.instrumentation.undertow;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.instrumentation.undertow.UndertowDecorator.DATADOG_UNDERTOW_CONTINUATION;
import static net.bytebuddy.matcher.ElementMatchers.isPrivate;
import static net.bytebuddy.matcher.ElementMatchers.not;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.appsec.api.blocking.BlockingException;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.api.gateway.Flow;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import io.undertow.server.HttpServerExchange;
import net.bytebuddy.asm.Advice;
import org.xnio.channels.StreamSinkChannel;

@AutoService(InstrumenterModule.class)
public class HttpServerExchangeSenderInstrumentation extends InstrumenterModule.AppSec
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {
  public HttpServerExchangeSenderInstrumentation() {
    super("undertow", "undertow-2.0");
  }

  @Override
  public String instrumentedType() {
    return "io.undertow.server.HttpServerExchange";
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".IgnoreSendAttribute",
      packageName + ".UndertowDecorator",
      packageName + ".UndertowExtractAdapter",
      packageName + ".UndertowExtractAdapter$Request",
      packageName + ".UndertowExtractAdapter$Response",
      packageName + ".UndertowBlockingHandler",
      packageName + ".HttpServerExchangeURIDataAdapter",
      packageName + ".UndertowBlockResponseFunction",
    };
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        not(isPrivate()).and(named("getResponseChannel")).and(takesArguments(0)),
        HttpServerExchangeSenderInstrumentation.class.getName() + "$GetResponseChannelAdvice");
  }

  /**
   * @see HttpServerExchange#getResponseChannel() ()
   */
  static class GetResponseChannelAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class, skipOn = Advice.OnNonDefaultValue.class)
    static boolean /* skip */ before(
        @Advice.This HttpServerExchange xchg,
        @Advice.FieldValue("responseChannel") StreamSinkChannel channel) {
      if (channel != null) {
        return false;
      }

      AgentScope.Continuation continuation = xchg.getAttachment(DATADOG_UNDERTOW_CONTINUATION);
      if (continuation == null) {
        return false;
      }
      if (xchg.getAttachment(IgnoreSendAttribute.IGNORE_SEND_KEY) != null) {
        return false;
      }
      xchg.putAttachment(IgnoreSendAttribute.IGNORE_SEND_KEY, IgnoreSendAttribute.INSTANCE);

      AgentSpan span = continuation.span();
      Flow<Void> flow =
          UndertowDecorator.DECORATE.callIGCallbackResponseAndHeaders(
              span, xchg, xchg.getStatusCode(), UndertowExtractAdapter.Response.GETTER);
      Flow.Action action = flow.getAction();
      if (!(action instanceof Flow.Action.RequestBlockingAction)) {
        return false;
      }

      Flow.Action.RequestBlockingAction rba = (Flow.Action.RequestBlockingAction) action;

      xchg.putAttachment(UndertowBlockingHandler.REQUEST_BLOCKING_DATA, rba);
      xchg.putAttachment(
          UndertowBlockingHandler.TRACE_SEGMENT, span.getRequestContext().getTraceSegment());
      UndertowBlockingHandler.INSTANCE.handleRequest(xchg);
      return true;
    }

    @Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class)
    static void after(
        @Advice.Enter boolean skip, @Advice.Thrown(readOnly = false) Throwable thrown) {
      if (!skip) {
        return;
      }

      thrown = new BlockingException("Request blocked (HttpServerExchange#getResponseChannel)");
    }
  }
}
