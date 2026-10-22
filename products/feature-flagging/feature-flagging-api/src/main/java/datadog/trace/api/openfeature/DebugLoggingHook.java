package datadog.trace.api.openfeature;

import datadog.trace.api.featureflag.ufc.v1.ServerConfiguration;
import datadog.trace.bootstrap.config.provider.ConfigProvider;
import dev.openfeature.sdk.EvaluationContext;
import dev.openfeature.sdk.FlagEvaluationDetails;
import dev.openfeature.sdk.Hook;
import dev.openfeature.sdk.HookContext;
import dev.openfeature.sdk.ImmutableStructure;
import dev.openfeature.sdk.ProviderEvaluation;
import dev.openfeature.sdk.Structure;
import dev.openfeature.sdk.Value;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Logs raw evaluation diagnostics at the provider and OpenFeature hook boundaries.
 *
 * <p>Raw values and context are logged only when both {@code DD_TRACE_DEBUG} and the application
 * logger allow DEBUG. Logging and formatting failures never reach the evaluation.
 */
final class DebugLoggingHook implements Hook<Object> {
  private static final Logger log = LoggerFactory.getLogger(DebugLoggingHook.class);

  /** Cached {@code DD_TRACE_DEBUG}; it also gates the provider's other debug diagnostics. */
  static final boolean TRACE_DEBUG = readTraceDebug();

  private static final Structure VALUE_CONVERTER = new ImmutableStructure();

  static DebugLoggingHook createIfEnabled() {
    return isEnabled() ? new DebugLoggingHook() : null;
  }

  static void resolution(
      String key,
      Object defaultValue,
      EvaluationContext context,
      ProviderEvaluation<?> result,
      ServerConfiguration config) {
    if (!isEnabled()) {
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
    if (!isEnabled()) {
      return;
    }
    try {
      log.debug(
          "Feature Flagging provider resolution error: flag={}, errorCode={}, errorMessage={}",
          key,
          code,
          message);
    } catch (Throwable ignored) {
      // Diagnostics must never interrupt evaluation.
    }
  }

  @Override
  public void finallyAfter(
      final HookContext<Object> context,
      final FlagEvaluationDetails<Object> details,
      final Map<String, Object> hints) {
    if (details == null || !isEnabled()) {
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

  private static boolean isEnabled() {
    try {
      return TRACE_DEBUG && log.isDebugEnabled();
    } catch (Throwable ignored) {
      return false;
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
    try {
      log.debug(
          "Feature Flagging evaluation diagnostic formatting failed; raw detail unavailable",
          failure);
    } catch (Throwable ignored) {
      // Diagnostics must never interrupt evaluation.
    }
  }

  private static boolean readTraceDebug() {
    try {
      return ConfigProvider.getInstance().getBoolean("trace.debug", false);
    } catch (Throwable ignored) {
      // Configuration is unavailable without the agent or with an older agent.
      return false;
    }
  }
}
