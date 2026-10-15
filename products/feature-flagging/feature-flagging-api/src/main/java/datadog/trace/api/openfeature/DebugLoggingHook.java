package datadog.trace.api.openfeature;

import dev.openfeature.sdk.FlagEvaluationDetails;
import dev.openfeature.sdk.Hook;
import dev.openfeature.sdk.HookContext;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Logs final evaluation outcomes without flag values, targeting context, or error messages. */
final class DebugLoggingHook implements Hook<Object> {
  private static final Logger log = LoggerFactory.getLogger(DebugLoggingHook.class);

  static DebugLoggingHook createIfEnabled() {
    return log.isDebugEnabled() ? new DebugLoggingHook() : null;
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
          "Feature Flagging evaluation: flag={}, reason={}, variant={}, errorCode={}",
          details.getFlagKey(),
          details.getReason(),
          details.getVariant(),
          details.getErrorCode());
    } catch (RuntimeException ignored) {
      // Diagnostics must not affect evaluation, even if an application logging backend fails.
    }
  }
}
