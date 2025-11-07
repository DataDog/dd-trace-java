package datadog.trace.instrumentation.kafka_clients;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.hasInterface;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static java.util.Collections.singletonMap;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.agent.tooling.muzzle.Reference;
import datadog.trace.api.iast.IastContext;
import datadog.trace.api.iast.Source;
import datadog.trace.api.iast.SourceTypes;
import datadog.trace.bootstrap.ContextStore;
import datadog.trace.bootstrap.InstrumentationContext;
import java.nio.ByteBuffer;
import java.util.Map;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import org.apache.kafka.common.serialization.Deserializer;

@AutoService(InstrumenterModule.class)
public class KafkaDeserializerInstrumentation extends InstrumenterModule.Iast
    implements Instrumenter.ForTypeHierarchy, Instrumenter.HasMethodAdvice {

  private static final String DESERIALIZER_CLASS =
      "org.apache.kafka.common.serialization.Deserializer";

  /** Ensure same compatibility as the tracer */
  static final Reference[] MUZZLE_CHECK = {
    new Reference.Builder("org.apache.kafka.clients.consumer.ConsumerRecord")
        .withMethod(
            new String[0],
            Reference.EXPECTS_PUBLIC | Reference.EXPECTS_NON_STATIC,
            "headers",
            "Lorg/apache/kafka/common/header/Headers;")
        .build()
  };

  public KafkaDeserializerInstrumentation() {
    super("kafka");
  }

  @Override
  public String muzzleDirective() {
    return "since-0.11";
  }

  @Override
  public String hierarchyMarkerType() {
    return DESERIALIZER_CLASS;
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return hasInterface(named(hierarchyMarkerType()));
  }

  @Override
  public Map<String, String> contextStore() {
    return singletonMap(DESERIALIZER_CLASS, Boolean.class.getName());
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {packageName + ".KafkaIastHelper"};
  }

  @Override
  public Reference[] additionalMuzzleReferences() {
    return MUZZLE_CHECK;
  }

  @Override
  public void methodAdvice(final MethodTransformer transformer) {
    final String baseName = KafkaDeserializerInstrumentation.class.getName();
    transformer.applyAdvice(
        named("configure").and(takesArguments(Map.class, boolean.class)),
        baseName + "$ConfigureAdvice");
    transformer.applyAdvice(
        named("deserialize").and(takesArguments(2)).and(takesArgument(1, byte[].class)),
        baseName + "$Deserialize2Advice");
    transformer.applyAdvice(
        named("deserialize").and(takesArguments(3)).and(takesArgument(2, byte[].class)),
        baseName + "$Deserialize3Advice");
    transformer.applyAdvice(
        named("deserialize").and(takesArguments(3)).and(takesArgument(2, ByteBuffer.class)),
        baseName + "$DeserializeByteBufferAdvice");
  }

  @SuppressWarnings("rawtypes")
  public static class ConfigureAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void configure(
        @Advice.This final Deserializer<?> deserializer, @Advice.Argument(1) final boolean isKey) {
      final ContextStore<Deserializer, Boolean> store =
          InstrumentationContext.get(Deserializer.class, Boolean.class);
      KafkaIastHelper.configure(store, deserializer, isKey);
    }
  }

  @SuppressWarnings("rawtypes")
  public static class Deserialize2Advice {

    @Source(SourceTypes.KAFKA_MESSAGE)
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void deserialize(
        @Advice.This final Deserializer<?> deserializer,
        @Advice.Argument(1) byte[] data,
        @Advice.Local("iastCtx") IastContext ctx) {
      final ContextStore<Deserializer, Boolean> store =
          InstrumentationContext.get(Deserializer.class, Boolean.class);
      ctx = KafkaIastHelper.beforeDeserialize(store, deserializer, data);
    }

    @Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class)
    public static void afterDeserialize(
        @Advice.This final Deserializer<?> deserializer,
        @Advice.Return Object result,
        @Advice.Local("iastCtx") IastContext ctx) {
      final ContextStore<Deserializer, Boolean> store =
          InstrumentationContext.get(Deserializer.class, Boolean.class);
      KafkaIastHelper.afterDeserialize(ctx, store, deserializer, result);
    }
  }

  @SuppressWarnings({"rawtypes", "JavaExistingMethodCanBeUsed"})
  public static class Deserialize3Advice {

    @Source(SourceTypes.KAFKA_MESSAGE)
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void deserialize(
        @Advice.This final Deserializer<?> deserializer,
        @Advice.Argument(2) byte[] data,
        @Advice.Local("iastCtx") IastContext ctx) {
      final ContextStore<Deserializer, Boolean> store =
          InstrumentationContext.get(Deserializer.class, Boolean.class);
      ctx = KafkaIastHelper.beforeDeserialize(store, deserializer, data);
    }

    @Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class)
    public static void afterDeserialize(
        @Advice.This final Deserializer<?> deserializer,
        @Advice.Return Object result,
        @Advice.Local("iastCtx") IastContext ctx) {
      final ContextStore<Deserializer, Boolean> store =
          InstrumentationContext.get(Deserializer.class, Boolean.class);
      KafkaIastHelper.afterDeserialize(ctx, store, deserializer, result);
    }
  }

  @SuppressWarnings({"rawtypes", "JavaExistingMethodCanBeUsed"})
  public static class DeserializeByteBufferAdvice {

    @Source(SourceTypes.KAFKA_MESSAGE)
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void deserialize(
        @Advice.This final Deserializer<?> deserializer,
        @Advice.Argument(2) ByteBuffer data,
        @Advice.Local("iastCtx") IastContext ctx) {
      final ContextStore<Deserializer, Boolean> store =
          InstrumentationContext.get(Deserializer.class, Boolean.class);
      ctx = KafkaIastHelper.beforeDeserialize(store, deserializer, data);
    }

    @Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class)
    public static void afterDeserialize(
        @Advice.This final Deserializer<?> deserializer,
        @Advice.Return final Object result,
        @Advice.Local("iastCtx") IastContext ctx) {
      final ContextStore<Deserializer, Boolean> store =
          InstrumentationContext.get(Deserializer.class, Boolean.class);
      KafkaIastHelper.afterDeserialize(ctx, store, deserializer, result);
    }
  }
}
