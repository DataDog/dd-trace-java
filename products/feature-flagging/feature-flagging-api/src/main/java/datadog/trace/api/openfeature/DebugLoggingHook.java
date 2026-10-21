package datadog.trace.api.openfeature;

import datadog.trace.api.featureflag.ufc.v1.ServerConfiguration;
import dev.openfeature.sdk.EvaluationContext;
import dev.openfeature.sdk.FlagEvaluationDetails;
import dev.openfeature.sdk.Hook;
import dev.openfeature.sdk.HookContext;
import dev.openfeature.sdk.ImmutableStructure;
import dev.openfeature.sdk.ProviderEvaluation;
import dev.openfeature.sdk.Structure;
import dev.openfeature.sdk.Value;
import java.util.Map;

/** Logs raw evaluation diagnostics at the provider and OpenFeature hook boundaries. */
final class DebugLoggingHook implements Hook<Object> {
  private static final DiagnosticLogger log = DiagnosticLogger.getLogger(DebugLoggingHook.class);
  private static final Structure VALUE_CONVERTER = new ImmutableStructure();

  static DebugLoggingHook createIfEnabled() {
    return log.isDebugEnabled() ? new DebugLoggingHook() : null;
  }

  static void resolution(
      String key,
      Object defaultValue,
      EvaluationContext context,
      ProviderEvaluation<?> result,
      ServerConfiguration config) {
    if (!log.isDebugEnabled()) {
      return;
    }
    try {
      log.debug(
          "Feature Flagging provider resolution: flag={}, targetingKey={}, context={}, defaultValue={}, resolvedValue={}, variant={}, reason={}, errorCode={}, errorMessage={}, metadata={}, configurationEnv={}, configurationCreatedAt={}",
          key,
          context == null ? null : context.getTargetingKey(),
          raw(context),
          raw(defaultValue),
          raw(result.getValue()),
          result.getVariant(),
          result.getReason(),
          result.getErrorCode(),
          result.getErrorMessage(),
          result.getFlagMetadata() == null
              ? null
              : raw(result.getFlagMetadata().asUnmodifiableMap()),
          config == null || config.environment == null ? null : config.environment.name,
          config == null ? null : config.createdAt);
    } catch (Throwable failure) {
      formattingFailed(failure);
    }
  }

  static void resolutionError(String key, Object code, String message) {
    if (log.isDebugEnabled()) {
      log.debug(
          "Feature Flagging provider resolution error: flag={}, errorCode={}, errorMessage={}",
          key,
          code,
          message);
    }
  }

  @Override
  public void finallyAfter(
      final HookContext<Object> context,
      final FlagEvaluationDetails<Object> details,
      final Map<String, Object> hints) {
    if (!log.isDebugEnabled() || details == null) {
      return;
    }
    try {
      log.debug(
          "Feature Flagging evaluation observed at finallyAfter: flag={}, targetingKey={}, context={}, defaultValue={}, observedValue={}, variant={}, reason={}, errorCode={}, errorMessage={}, metadata={}",
          details.getFlagKey(),
          context == null || context.getCtx() == null ? null : context.getCtx().getTargetingKey(),
          context == null ? null : raw(context.getCtx()),
          context == null ? null : raw(context.getDefaultValue()),
          raw(details.getValue()),
          details.getVariant(),
          details.getReason(),
          details.getErrorCode(),
          details.getErrorMessage(),
          details.getFlagMetadata() == null
              ? null
              : raw(details.getFlagMetadata().asUnmodifiableMap()));
    } catch (Throwable failure) {
      formattingFailed(failure);
    }
  }

  /**
   * Materialize inside the diagnostic boundary, including user supplied structure implementations.
   */
  private static String raw(Object value) {
    if (value instanceof Value) {
      value = VALUE_CONVERTER.convertValue((Value) value);
    } else if (value instanceof Structure) {
      value = ((Structure) value).asObjectMap();
    }
    return String.valueOf(value);
  }

  private static void formattingFailed(Throwable failure) {
    log.debug(
        "Feature Flagging evaluation diagnostic formatting failed; raw detail unavailable",
        failure);
  }
}
