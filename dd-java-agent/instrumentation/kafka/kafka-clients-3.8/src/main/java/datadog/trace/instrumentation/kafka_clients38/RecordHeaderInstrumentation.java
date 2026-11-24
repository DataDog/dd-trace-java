package datadog.trace.instrumentation.kafka_clients38;

import static datadog.trace.agent.tooling.bytebuddy.matcher.ClassLoaderMatchers.hasClassNamed;
import static net.bytebuddy.matcher.ElementMatchers.isConstructor;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import java.nio.ByteBuffer;
import net.bytebuddy.matcher.ElementMatcher;

/** Registers the lookup from Kafka's buffered-header constructor. */
@AutoService(InstrumenterModule.class)
public final class RecordHeaderInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {
  public RecordHeaderInstrumentation() {
    super("kafka", "kafka-3.8");
  }

  @Override
  public ElementMatcher.Junction<ClassLoader> classLoaderMatcher() {
    return hasClassNamed("org.apache.kafka.clients.MetadataRecoveryStrategy");
  }

  @Override
  public String instrumentedType() {
    return "org.apache.kafka.common.header.internals.RecordHeader";
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {packageName + ".RecordHeaderAccess"};
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isConstructor().and(takesArguments(ByteBuffer.class, ByteBuffer.class)),
        packageName + ".RecordHeaderConstructorAdvice");
  }
}
