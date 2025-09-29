package datadog.trace.instrumentation.springmessaging;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.api.datastreams.DataStreamsContext.create;
import static datadog.trace.api.datastreams.DataStreamsTags.Direction.INBOUND;
import static datadog.trace.bootstrap.instrumentation.api.AgentPropagation.extractContextAndGetSpanContext;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.*;
import static datadog.trace.instrumentation.springmessaging.SpringMessageDecorator.DECORATE;
import static datadog.trace.instrumentation.springmessaging.SpringMessageDecorator.SPRING_INBOUND;
import static datadog.trace.instrumentation.springmessaging.SpringMessageExtractAdapter.GETTER;
import static java.util.Collections.singletonMap;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.api.datastreams.DataStreamsTags;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentSpanContext;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.bootstrap.instrumentation.java.concurrent.State;
import java.util.Map;
import net.bytebuddy.asm.Advice;
import org.springframework.messaging.Message;
import org.springframework.messaging.handler.invocation.InvocableHandlerMethod;

@AutoService(InstrumenterModule.class)
public final class SpringMessageHandlerInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

  public SpringMessageHandlerInstrumentation() {
    super("spring-messaging", "spring-messaging-4");
  }

  @Override
  public String instrumentedType() {
    return "org.springframework.messaging.handler.invocation.InvocableHandlerMethod";
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod()
            .and(
                named("invoke")
                    .and(takesArgument(0, named("org.springframework.messaging.Message")))),
        SpringMessageHandlerInstrumentation.class.getName() + "$HandleMessageAdvice");
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".SpringMessageDecorator",
      packageName + ".SpringMessageExtractAdapter",
      packageName + ".SpringMessageExtractAdapter$1"
    };
  }

  @Override
  public Map<String, String> contextStore() {
    return singletonMap("org.springframework.messaging.Message", State.class.getName());
  }

  public static class HandleMessageAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static AgentScope onEnter(
        @Advice.This InvocableHandlerMethod thiz, @Advice.Argument(0) Message<?> message) {
      AgentSpanContext parentContext;
      AgentSpan parent = activeSpan();
      
      // First try to get context from continuation (preferred method)
      State state = InstrumentationContext.get(Message.class, State.class).get(message);
      if (null != state) {
        System.out.println("[Spring] Found state in Spring message, attempting to activate continuation on thread: " + 
                          Thread.currentThread().getId());
        AgentScope.Continuation continuation = state.getAndResetContinuation();
        if (null != continuation) {
          try (AgentScope scope = continuation.activate()) {
            AgentSpan span = startSpan(SPRING_INBOUND);
            DECORATE.afterStart(span);
            span.setResourceName(DECORATE.spanNameForMethod(thiz.getMethod()));
            System.out.println("[Spring] Successfully activated continuation from Spring Message with span: " + 
                              span.getSpanId() + " on thread: " + Thread.currentThread().getId());
            return activateSpan(span);
          }
        } else {
          System.out.println("[Spring] No continuation found in state on thread: " + Thread.currentThread().getId());
        }
      } else {
        System.out.println("[Spring] No state found in Spring message 2, falling back to header extraction on thread: " + 
                          Thread.currentThread().getId());
      }
      
      // Fallback to existing context or header extraction
      if (null != parent) {
        // prefer existing context, assume it was already extracted from this message
        parentContext = parent.context();
        System.out.println("[Spring] Using existing active span context on thread: " + Thread.currentThread().getId());
      } else {
        // otherwise try to re-extract the message context to avoid disconnected trace
        parentContext = extractContextAndGetSpanContext(message, GETTER);
        System.out.println("[Spring] Extracted context from message headers on thread: " + Thread.currentThread().getId());
      }
      
      AgentSpan span = startSpan(SPRING_INBOUND, parentContext);
      DECORATE.afterStart(span);
      span.setResourceName(DECORATE.spanNameForMethod(thiz.getMethod()));
      
      // Extract SQS queue information - try different header patterns
      Object queueUrl = message.getHeaders().get("Sqs_QueueUrl");
      Object queueName = message.getHeaders().get("Sqs_QueueName");
      
      // If not found in Sqs_ prefixed headers, try aws. prefixed headers
      if (queueUrl == null) {
        queueUrl = message.getHeaders().get("aws.queue.url");
      }
      if (queueName == null) {
        queueName = message.getHeaders().get("aws.queue.name");
      }
      
      // If still not found, try to extract from QueueAttributes
      if (queueUrl == null || queueName == null) {
        Object queueAttributes = message.getHeaders().get("Sqs_QueueAttributes");
        if (queueAttributes != null) {
          String attributesStr = queueAttributes.toString();
          // Extract queue name from attributes if available
          if (queueName == null && attributesStr.contains("queueName=")) {
            queueName = attributesStr.substring(attributesStr.indexOf("queueName=") + 10).split(",")[0];
          }
        }
      }
      
      // Add SQS queue tags to the span
      if (queueUrl != null) {
        span.setTag("aws.sqs.queue_url", queueUrl.toString());
      }
      if (queueName != null) {
        span.setTag("aws.sqs.queue_name", queueName.toString());
      }
      
      return activateSpan(span);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void onExit(@Advice.Enter AgentScope scope, @Advice.Thrown Throwable error) {
      if (null == scope) {
        return;
      }
      AgentSpan span = scope.span();
      if (null != error) {
        DECORATE.onError(span, error);
      }
      scope.close();
      DECORATE.beforeFinish(span);
      span.finish();
    }
  }
}
