package datadog.trace.instrumentation.confluentschemaregistry;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.returns;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.Instrumenter.MethodTransformer;
import net.bytebuddy.asm.Advice;

/**
 * Instruments the CachedSchemaRegistryClient to capture schema registration and compatibility check
 * operations.
 */
public class SchemaRegistryClientInstrumentation
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

  @Override
  public String instrumentedType() {
    return "io.confluent.kafka.schemaregistry.client.CachedSchemaRegistryClient";
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    // Instrument register(String subject, Schema schema)
    transformer.applyAdvice(
        isMethod()
            .and(named("register"))
            .and(isPublic())
            .and(takesArguments(2))
            .and(takesArgument(0, String.class))
            .and(returns(int.class)),
        getClass().getName() + "$RegisterAdvice");

    // Instrument testCompatibility(String subject, Schema schema)
    transformer.applyAdvice(
        isMethod()
            .and(named("testCompatibility"))
            .and(isPublic())
            .and(takesArguments(2))
            .and(takesArgument(0, String.class))
            .and(returns(boolean.class)),
        getClass().getName() + "$TestCompatibilityAdvice");

    // Instrument getSchemaById(int id)
    transformer.applyAdvice(
        isMethod()
            .and(named("getSchemaById"))
            .and(isPublic())
            .and(takesArguments(1))
            .and(takesArgument(0, int.class)),
        getClass().getName() + "$GetSchemaByIdAdvice");
  }

  public static class RegisterAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void onEnter(@Advice.Argument(0) String subject) {
      // Track that we're attempting registration
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void onExit(
        @Advice.Argument(0) String subject,
        @Advice.Return int schemaId,
        @Advice.Thrown Throwable throwable) {

      String topic = SchemaRegistryContext.getTopic();
      Boolean isKey = SchemaRegistryContext.getIsKey();

      if (throwable != null) {
        // Registration failed - likely due to compatibility issues
        String errorMessage = throwable.getMessage();
        SchemaRegistryMetrics.recordSchemaRegistrationFailure(
            subject, errorMessage, isKey != null ? isKey : false, topic);

        // Also log that this is a compatibility failure
        if (errorMessage != null
            && (errorMessage.contains("incompatible") || errorMessage.contains("compatibility"))) {
          SchemaRegistryMetrics.recordCompatibilityCheck(subject, false, errorMessage);
        }
      } else {
        // Registration successful
        SchemaRegistryMetrics.recordSchemaRegistration(
            subject, schemaId, isKey != null ? isKey : false, topic);

        // Store the schema ID in context
        if (isKey != null && isKey) {
          SchemaRegistryContext.setKeySchemaId(schemaId);
        } else {
          SchemaRegistryContext.setValueSchemaId(schemaId);
        }
      }
    }
  }

  public static class TestCompatibilityAdvice {
    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void onExit(
        @Advice.Argument(0) String subject,
        @Advice.Return boolean compatible,
        @Advice.Thrown Throwable throwable) {

      if (throwable != null) {
        SchemaRegistryMetrics.recordCompatibilityCheck(subject, false, throwable.getMessage());
      } else {
        SchemaRegistryMetrics.recordCompatibilityCheck(
            subject, compatible, compatible ? null : "Schema is not compatible");
      }
    }
  }

  public static class GetSchemaByIdAdvice {
    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void onExit(
        @Advice.Argument(0) int schemaId,
        @Advice.Return Object schema,
        @Advice.Thrown Throwable throwable) {

      if (throwable == null && schema != null) {
        String schemaType = schema.getClass().getSimpleName();
        SchemaRegistryMetrics.recordSchemaRetrieval(schemaId, schemaType);
      }
    }
  }
}
