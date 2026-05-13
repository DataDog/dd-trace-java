package datadog.trace.instrumentation.springweb6;

import static datadog.trace.instrumentation.springweb6.SpringWebHttpServerDecorator.handlerSpanKeysFor;

import datadog.trace.api.Pair;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.java.concurrent.AsyncResultExtensions;
import java.util.concurrent.CompletionStage;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.implementation.bytecode.assign.Assigner;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.method.support.InvocableHandlerMethod;

public class WrapContinuableResultAdvice {
  @Advice.OnMethodExit(suppress = Throwable.class)
  public static void after(
      @Advice.Argument(value = 0, typing = Assigner.Typing.DYNAMIC)
          final NativeWebRequest nativeWebRequest,
      @Advice.Return(readOnly = false) Object result,
      @Advice.This final InvocableHandlerMethod self) {
    if (!(nativeWebRequest instanceof ServletWebRequest) || !(result instanceof CompletionStage)) {
      return;
    }

    ServletWebRequest servletWebRequest = (ServletWebRequest) nativeWebRequest;
    final Pair<String, String> handlerSpanKeys = handlerSpanKeysFor(self.getBean());

    if (Boolean.TRUE.equals(
        servletWebRequest.getAttribute(
            handlerSpanKeys.getRight(), ServletWebRequest.SCOPE_REQUEST))) {
      return;
    }
    Object span =
        servletWebRequest.getAttribute(handlerSpanKeys.getLeft(), ServletWebRequest.SCOPE_REQUEST);
    if (!(span instanceof AgentSpan)) {
      return;
    }
    servletWebRequest.setAttribute(
        handlerSpanKeys.getRight(), true, ServletWebRequest.SCOPE_REQUEST);
    result =
        ((CompletionStage<?>) result)
            .whenComplete(AsyncResultExtensions.finishSpan((AgentSpan) span));
  }
}
