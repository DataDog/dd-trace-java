package datadog.trace.instrumentation.kafka_clients;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.api.iast.VulnerabilityMarks.NOT_MARKED;
import static datadog.trace.instrumentation.kafka_clients.KafkaDeserializerInstrumentation.MUZZLE_CHECK;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.agent.tooling.muzzle.Reference;
import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.Propagation;
import datadog.trace.api.iast.propagation.PropagationModule;
import java.nio.ByteBuffer;
import net.bytebuddy.asm.Advice;

@AutoService(InstrumenterModule.class)
public class UtilsInstrumentation extends InstrumenterModule.Iast
    implements Instrumenter.ForSingleType {

  public UtilsInstrumentation() {
    super("kafka");
  }

  @Override
  public Reference[] additionalMuzzleReferences() {
    return MUZZLE_CHECK;
  }

  @Override
  public String instrumentedType() {
    return "org.apache.kafka.common.utils.Utils";
  }

  @Override
  public void methodAdvice(final MethodTransformer transformer) {
    final String baseName = UtilsInstrumentation.class.getName();
    transformer.applyAdvice(
        named("toArray").and(takesArguments(ByteBuffer.class, int.class, int.class)),
        baseName + "$ToArrayAdvice");
    transformer.applyAdvice(
        named("wrapNullable").and(takesArguments(byte[].class)), baseName + "$WrapAdvice");
  }

  public static class ToArrayAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    @Propagation
    public static void toArray(
        @Advice.Argument(0) final ByteBuffer buffer,
        @Advice.Argument(1) final int offset,
        @Advice.Argument(2) final int length,
        @Advice.Return final byte[] bytes) {
      if (buffer == null || bytes == null || bytes.length == 0) {
        return;
      }
      final PropagationModule propagation = InstrumentationBridge.PROPAGATION;
      if (propagation == null) {
        return;
      }
      int start = buffer.position() + offset;
      if (buffer.hasArray()) {
        start += buffer.arrayOffset();
      }
      // create a new range shifted to the result byte array coordinates
      propagation.taintObjectIfRangeTainted(bytes, buffer, start, length, false, NOT_MARKED);
    }
  }

  public static class WrapAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    @Propagation
    public static void wrapNullable(
        @Advice.Argument(0) final byte[] bytes, @Advice.Return final ByteBuffer buffer) {
      if (buffer == null || bytes == null || bytes.length == 0) {
        return;
      }
      final PropagationModule propagation = InstrumentationBridge.PROPAGATION;
      if (propagation == null) {
        return;
      }
      propagation.taintObjectIfTainted(buffer, bytes, true, NOT_MARKED);
    }
  }
}
