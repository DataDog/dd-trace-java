package datadog.trace.instrumentation.mqttv5;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

import java.io.InputStream;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.implementsInterface;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.*;

@AutoService(InstrumenterModule.class)
public class MqttInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForTypeHierarchy , Instrumenter.HasMethodAdvice{
  public MqttInstrumentation() {
    super("mqtt");
  }

  @Override
  public String hierarchyMarkerType() {
    return "org.eclipse.paho.mqttv5.client.IMqttAsyncClient";
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return implementsInterface(named(hierarchyMarkerType()));
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
//    transformer.applyAdvice(
//        isConstructor()
//            .and(takesArguments(5))
//            .and(takesArgument(0, String.class))
//            .and(takesArgument(1, String.class)),
//        MqttInstrumentation.class.getName() + "$ConstructorAdvice");

    transformer.applyAdvice(
        isMethod()
            .and(named("publish"))
            .and(takesArguments(4))
            .and(takesArgument(0, named("java.lang.String")))
            .and(takesArgument(1, named("org.eclipse.paho.mqttv5.common.MqttMessage"))),
        packageName + ".MqttPublishAdvice");
  }
  @Override
  public String[] helperClassNames() {
    return new String[]{
        packageName + ".MqttCallBackAdvice",
        packageName + ".MqttDecorator",
        packageName + ".UserPropertyInjectAdapter",
        packageName + ".UserPropertyExtractAdapter"
    };
  }
}
