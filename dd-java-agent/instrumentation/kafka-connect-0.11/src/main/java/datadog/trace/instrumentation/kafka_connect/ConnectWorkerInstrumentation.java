package datadog.trace.instrumentation.kafka_connect;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import net.bytebuddy.asm.Advice;
import org.apache.kafka.connect.util.ConnectorTaskId;
import org.apache.kafka.connect.runtime.TaskStatus.Listener;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.*;

@AutoService(InstrumenterModule.class)
public final class ConnectWorkerInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForSingleType {

  public ConnectWorkerInstrumentation() {
    super("kafka", "kafka-connect");
  }

  @Override
  public String instrumentedType() {
    return "org.apache.kafka.connect.runtime.WorkerTask";
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isConstructor()
            .and(takesArgument(0, named("org.apache.kafka.connect.util.ConnectorTaskId")))
            .and(takesArgument(1, named("org.apache.kafka.connect.runtime.TaskStatus.Listener"))),
        ConnectWorkerInstrumentation.class.getName() + "$ConstructorAdvice");
  }

  public static class ConstructorAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void wrap(
        @Advice.Argument(value = 0, readOnly = true) ConnectorTaskId id,
        @Advice.Argument(value = 1, readOnly = false) Listener statusListen
        ) {
      System.out.println("building worker task!!");
    }
  }
}
