package datadog.trace.instrumentation.kafka_connect;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.extendsClass;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isConstructor;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesNoArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.instrumentation.kafka_common.ConnectInternalTopics;
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
  public String hierarchyMarkerType() {
    return TARGET_TYPE;
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".TaskListener",
      "datadog.trace.instrumentation.kafka_common.ConnectInternalTopics",
    };
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
    // WorkerTask.run() calls the task's start() before the status listener's onStartup, so set
    // the connector's service name for the whole run. Threads the task creates inherit it.
    transformer.applyAdvice(
        isMethod().and(named("run")).and(takesNoArguments()),
        ConnectWorkerInstrumentation.class.getName() + "$RunAdvice");
  }

  public static class ConstructorAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void wrap(
        @Advice.Argument(value = 0, readOnly = true) ConnectorTaskId id,
        @Advice.Argument(value = 1, readOnly = false) Listener statusListener) {
      statusListener = new TaskListener(statusListener);
      ConnectInternalTopics.markConnectWorker();
    }

    public static void muzzleCheck(ConsumerRecord record) {
      // KafkaConsumerInstrumentation only applies for kafka versions with headers
      // Make an explicit call so ConsumerCoordinatorInstrumentation does the same
      record.headers();
    }
  }

  public static class RunAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void onEnter(@Advice.FieldValue("id") ConnectorTaskId id) {
      AgentTracer.get().getDataStreamsMonitoring().setThreadServiceName(id.connector());
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void onExit() {
      AgentTracer.get().getDataStreamsMonitoring().clearThreadServiceName();
    }
  }
}
