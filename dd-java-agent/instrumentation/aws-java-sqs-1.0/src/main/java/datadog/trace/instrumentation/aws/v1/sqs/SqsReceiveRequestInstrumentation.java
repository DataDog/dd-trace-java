package datadog.trace.instrumentation.aws.v1.sqs;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.namedOneOf;
import static java.util.Collections.singletonMap;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.returns;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.amazonaws.services.sqs.model.ReceiveMessageRequest;
import com.amazonaws.services.sqs.model.ReceiveMessageResult;
import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.api.Config;
import datadog.trace.bootstrap.InstrumentationContext;
import java.util.List;
import java.util.Map;
import net.bytebuddy.asm.Advice;

@AutoService(Instrumenter.class)
public class SqsReceiveRequestInstrumentation extends AbstractSqsInstrumentation
    implements Instrumenter.ForSingleType {

  @Override
  public String instrumentedType() {
    return "com.amazonaws.services.sqs.AmazonSQSClient";
  }

  @Override
  public Map<String, String> contextStore() {
    return singletonMap(
        "com.amazonaws.services.sqs.model.ReceiveMessageResult", "java.lang.String");
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        isMethod()
            .and(namedOneOf("receiveMessage", "executeReceiveMessage"))
            .and(takesArgument(0, named("com.amazonaws.services.sqs.model.ReceiveMessageRequest")))
            .and(returns(named("com.amazonaws.services.sqs.model.ReceiveMessageResult"))),
        getClass().getName() + "$ReceiveMessageRequestAdvice");
  }

  public static class ReceiveMessageRequestAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void onEnter(@Advice.Argument(0) ReceiveMessageRequest request) {
      if (Config.get().isSqsPropagationEnabled()) {
        // ReceiveMessageRequest always returns a mutable list which we can append to
        List<String> attributeNames = request.getAttributeNames();
        for (String name : attributeNames) {
          if ("AWSTraceHeader".equals(name) || "All".equals(name)) {
            return;
          }
        }
        attributeNames.add("AWSTraceHeader");
      }
    }

    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void onExit(
        @Advice.Argument(0) ReceiveMessageRequest request,
        @Advice.Return ReceiveMessageResult result) {
      // store queueUrl inside response for SqsReceiveResultInstrumentation
      InstrumentationContext.get(ReceiveMessageResult.class, String.class)
          .put(result, request.getQueueUrl());
    }
  }
}
