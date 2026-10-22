package datadog.trace.instrumentation.openfeature;

import static net.bytebuddy.asm.Advice.Argument;
import static net.bytebuddy.asm.Advice.OnMethodEnter;
import static net.bytebuddy.asm.Advice.OnMethodExit;
import static net.bytebuddy.asm.Advice.OnNonDefaultValue;
import static net.bytebuddy.asm.Advice.Return;

import dev.openfeature.sdk.ErrorCode;
import dev.openfeature.sdk.ProviderEvaluation;
import dev.openfeature.sdk.Reason;
import org.slf4j.LoggerFactory;

/** Avoids linking legacy evaluators to the removed agent bootstrap API. */
@SuppressWarnings("unused")
public class LegacyProviderAdvice {
  public static class Initialize {
    @OnMethodEnter(skipOn = OnNonDefaultValue.class)
    public static boolean skipLegacyInitialization() {
      return true;
    }

    @OnMethodExit(suppress = Throwable.class)
    public static void warn() {
      LoggerFactory.getLogger("datadog.trace.api.openfeature.Provider")
          .warn(
              "This dd-openfeature version is incompatible with the Datadog Java agent;"
                  + " returning caller defaults. Upgrade dd-openfeature to enable Feature Flags.");
    }
  }

  public static class Evaluation {
    @OnMethodEnter(skipOn = OnNonDefaultValue.class)
    public static boolean skipLegacyEvaluation() {
      return true;
    }

    @OnMethodExit(suppress = Throwable.class)
    public static void returnDefault(
        @Argument(1) final Object defaultValue,
        @Return(readOnly = false) ProviderEvaluation<?> evaluation) {
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
