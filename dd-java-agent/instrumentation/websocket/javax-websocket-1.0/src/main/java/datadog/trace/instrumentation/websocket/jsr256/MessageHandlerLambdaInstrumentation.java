package datadog.trace.instrumentation.websocket.jsr256;

import static net.bytebuddy.matcher.ElementMatchers.any;

import datadog.trace.agent.tooling.Instrumenter;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

public final class MessageHandlerLambdaInstrumentation
    implements Instrumenter.ForLambda, Instrumenter.HasMethodAdvice {
  private final String lambdaInterface;

  public MessageHandlerLambdaInstrumentation(String namespace, String handlerType) {
    this.lambdaInterface = namespace + ".websocket.MessageHandler$" + handlerType;
  }

  @Override
  public String lambdaInterface() {
    return lambdaInterface;
  }

  @Override
  public ElementMatcher<TypeDescription> lambdaMatcher() {
    return any();
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    MessageHandlerInstrumentation.applyOnMessageAdvice(transformer);
  }
}
