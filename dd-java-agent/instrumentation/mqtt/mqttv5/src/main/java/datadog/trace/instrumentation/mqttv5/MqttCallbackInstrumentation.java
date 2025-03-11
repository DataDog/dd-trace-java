package datadog.trace.instrumentation.mqttv5;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.implementsInterface;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.*;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

@AutoService(InstrumenterModule.class)
public class MqttCallbackInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForTypeHierarchy , Instrumenter.HasMethodAdvice{

  public MqttCallbackInstrumentation() {
    super("mqtt");
  }

  @Override
  public String hierarchyMarkerType() {
    return "org.eclipse.paho.mqttv5.client.MqttCallback";
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return implementsInterface(named(hierarchyMarkerType()));
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod()
            .and(named("messageArrived"))
            .and(takesArguments(2))
            .and(takesArgument(0, named("java.lang.String")))
            .and(takesArgument(1, named("org.eclipse.paho.mqttv5.common.MqttMessage"))),
        packageName + ".MqttCallBackAdvice");
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
