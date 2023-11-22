package datadog.trace.instrumentation.pulsar;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.implementation.bytecode.assign.Assigner;
import net.bytebuddy.matcher.ElementMatcher;
import org.apache.pulsar.client.api.MessageListener;
import org.apache.pulsar.client.impl.conf.ConsumerConfigurationData;

@AutoService(Instrumenter.class)
public class MessageListenerInstrumentation extends Instrumenter.Tracing
    implements Instrumenter.ForTypeHierarchy {

  public MessageListenerInstrumentation() {
    super("pulsar");
  }

/*  @Override
  public String[] knownMatchingTypes() {
    return new String[] {
      "org.apache.pulsar.client.impl.conf.ConsumerConfigurationData",
    };
  }*/
   
  public static final String CLASS_NAME = "org.apache.pulsar.client.impl.conf.ConsumerConfigurationData";
  @Override
  public String hierarchyMarkerType() {
    return CLASS_NAME;
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return named(hierarchyMarkerType());
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
        packageName + ".ConsumerDecorator",
        packageName + ".UrlParser",
        packageName + ".UrlData",
        packageName + ".ProducerData",
        packageName + ".BasePulsarRequest",
        packageName + ".MessageTextMapGetter",
        packageName + ".MessageTextMapSetter",
        packageName + ".PulsarBatchRequest",
        packageName + ".PulsarRequest",
        packageName + ".MessageStore",
        packageName + ".MessageListenerWrapper",

    };
  }   
  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    String className = MessageListenerInstrumentation.class.getName();
    System.out.println("--------adviceTransformations------MessageListenerInstrumentation-- ");
/*    transformation.applyAdvice(
        isMethod()
            .and(isProtected())
            .and(named("internalReceive"))
            .and(takesArguments(2))
            .and(takesArgument(1, named("java.util.concurrent.TimeUnit"))),
        className + "$ConsumerInternalReceiveAdvice");*/

    transformation.applyAdvice(
        isMethod().and(isPublic()).and(named("getMessageListener")),
        className + "$ConsumerConfigurationDataMethodAdvice");
  }


  public static class ConsumerConfigurationDataMethodAdvice {

    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void after(
        @Advice.This ConsumerConfigurationData<?> data,
        @Advice.Return(readOnly = false, typing = Assigner.Typing.DYNAMIC)
            MessageListener<?> listener) {
      System.out.println("-----------------into ConsumerConfigurationDataMethodAdvice");
      if (listener == null) {
        return;
      }

      listener = new MessageListenerWrapper<>(listener);
    }
  }
}
