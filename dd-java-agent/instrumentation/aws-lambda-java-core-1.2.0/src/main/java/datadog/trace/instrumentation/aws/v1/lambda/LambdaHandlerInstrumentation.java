package datadog.trace.instrumentation.aws.v1.sqs;

import static com.amazon.sqs.javamessaging.SQSMessagingClientConstants.STRING;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isConstructor;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.amazon.sqs.javamessaging.message.SQSMessage;
import com.amazonaws.services.sqs.model.Message;
import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.api.Config;
import java.util.Map;
import javax.jms.JMSException;
import net.bytebuddy.asm.Advice;

@AutoService(Instrumenter.class)
public class LambdaHandlerInstrumentation extends Instrumenter.Tracing
    implements Instrumenter.ForSingleType {
  public LambdaHandlerInstrumentation() {
    super("aws-sdk");
  }

  @Override
  protected boolean defaultEnabled() {
    return true;
  }

  @Override
  public String instrumentedType() {
    return "com.amazonaws.services.lambda.runtime.RequestHandler";
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        method(ElementMatchers.nameContains("handleRequest")),
        getClass().getName() + "$ExtensionCommunicationAdvice");
  }

  public static class ExtensionCommunicationAdvice {
    @OnMethodEnter
    static long enter() {
      System.out.println("[maxday-poc-java-no-code] - Enter the function");
      return System.currentTimeMillis();
    }

    @OnMethodExit
    static void exit(@Origin String method, @Enter long start, @AllArguments Object[] args) {
      System.out.println("[maxday-poc-java-no-code] - Exit the function");
      System.out.println("[maxday-poc-java-no-code] - Took " + (System.currentTimeMillis() - start) + " milliseconds ");
    }
  }
}
