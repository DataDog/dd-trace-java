package datadog.trace.instrumentation.kafka_connect;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.extendsClass;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.*;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.connect.runtime.TaskStatus.Listener;
import org.apache.kafka.connect.util.ConnectorTaskId;

@AutoService(InstrumenterModule.class)
public final class ConnectWorkerInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForTypeHierarchy, Instrumenter.HasMethodAdvice {

  static final String TARGET_TYPE = "org.apache.kafka.connect.runtime.WorkerTask";

  public ConnectWorkerInstrumentation() {
    super("kafka", "kafka-connect");
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".TaskListener",
    };
  }

  @Override
  public String hierarchyMarkerType() {
    return TARGET_TYPE;
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return extendsClass(named(hierarchyMarkerType()));
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isConstructor()
            .and(takesArgument(0, named("org.apache.kafka.connect.util.ConnectorTaskId")))
            .and(takesArgument(1, named("org.apache.kafka.connect.runtime.TaskStatus$Listener"))),
        ConnectWorkerInstrumentation.class.getName() + "$ConstructorAdvice");
  }

  public static class ConstructorAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void wrap(
        @Advice.Argument(value = 0, readOnly = true) ConnectorTaskId id,
        @Advice.Argument(value = 1, readOnly = false) Listener statusListener) {
      statusListener = new TaskListener(statusListener);
    }

    public static void muzzleCheck(ConsumerRecord record) {
      // KafkaConsumerInstrumentation only applies for kafka versions with headers
      // Make an explicit call so ConsumerCoordinatorInstrumentation does the same
      record.headers();
    }
  }
}
