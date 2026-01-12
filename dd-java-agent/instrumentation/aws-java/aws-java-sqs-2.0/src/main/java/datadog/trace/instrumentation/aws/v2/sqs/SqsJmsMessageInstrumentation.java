package datadog.trace.instrumentation.aws.v2.sqs;

import static com.amazon.sqs.javamessaging.SQSMessagingClientConstants.STRING;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isConstructor;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.amazon.sqs.javamessaging.message.SQSMessage;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.api.Config;
import java.util.HashMap;
import java.util.Map;
import javax.jms.JMSException;
import net.bytebuddy.asm.Advice;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.MessageAttributeValue;

public final class SqsJmsMessageInstrumentation
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

  @Override
  public String instrumentedType() {
    return "com.amazon.sqs.javamessaging.message.SQSMessage";
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isConstructor()
            .and(takesArgument(2, named("software.amazon.awssdk.services.sqs.model.Message"))),
        getClass().getName() + "$JmsSqsMessageConstructorAdvice");
  }

  public static class JmsSqsMessageConstructorAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void onEnter(@Advice.Argument(value = 2, readOnly = false) Message sqsMessage) {
      Map<String, MessageAttributeValue> messageAttributes = sqsMessage.messageAttributes();
      MessageAttributeValue ddAttribute = messageAttributes.get("_datadog");
      if (ddAttribute != null && "Binary".equals(ddAttribute.dataType())) {
        // binary message attributes are not supported by amazon-sqs-java-messaging-lib, and there
        // is a chance we might introduce one, either when the message was sent from SNS or from a
        // DD-instrumented Javascript app.
        // When we reach this point, the value would already have been used by the aws-sqs
        // instrumentation, so we can safely remove it.
        Map<String, MessageAttributeValue> messageAttributesCopy = new HashMap<>(messageAttributes);
        // need to copy to remove because the original is an UnmodifiableMap
        messageAttributesCopy.remove("_datadog");
        sqsMessage = sqsMessage.toBuilder().messageAttributes(messageAttributesCopy).build();
      }
    }

    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void onExit(
        @Advice.Argument(2) Message sqsMessage, @Advice.FieldValue("properties") Map properties)
        throws JMSException {
      if (Config.get().isSqsPropagationEnabled()) {
        Map<String, String> systemAttributes = sqsMessage.attributesAsStrings();
        if (null != systemAttributes) {
          String awsTraceHeader = systemAttributes.get("AWSTraceHeader");
          if (null != awsTraceHeader && !awsTraceHeader.isEmpty()) {
            properties.put(
                "x__dash__amzn__dash__trace__dash__id", // X-Amzn-Trace-Id, encoded for JMS
                new SQSMessage.JMSMessagePropertyValue(awsTraceHeader, STRING));
          }
        }
      }
    }
  }
}
