package datadog.trace.instrumentation.openfeature;

import dev.openfeature.sdk.ErrorCode;
import dev.openfeature.sdk.ProviderEvaluation;
import dev.openfeature.sdk.Reason;
import net.bytebuddy.asm.Advice;
import org.slf4j.LoggerFactory;

/** Avoids linking legacy evaluators to the removed agent bootstrap API. */
public class LegacyProviderAdvice {
  public static class Initialize {
    @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
    public static boolean skipLegacyInitialization() {
      return true;
    }

    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void warn() {
      LoggerFactory.getLogger("datadog.trace.api.openfeature.Provider")
          .warn(
              "This dd-openfeature version is incompatible with the Datadog Java agent;"
                  + " returning caller defaults. Upgrade dd-openfeature to enable Feature Flags.");
    }
  }

  public static class Evaluation {
    @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
    public static boolean skipLegacyEvaluation() {
      return true;
    }

    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void returnDefault(
        @Advice.Argument(1) final Object defaultValue,
        @Advice.Return(readOnly = false) ProviderEvaluation<?> evaluation) {
      evaluation =
          ProviderEvaluation.builder()
              .value(defaultValue)
              .reason(Reason.ERROR.name())
              .errorCode(ErrorCode.PROVIDER_NOT_READY)
              .errorMessage("Upgrade dd-openfeature to enable Feature Flags with this Java agent")
              .build();
    }
  }
}
