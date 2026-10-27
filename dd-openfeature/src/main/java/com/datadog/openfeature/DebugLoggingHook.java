package com.datadog.openfeature;

import dev.openfeature.sdk.EvaluationContext;
import dev.openfeature.sdk.FlagEvaluationDetails;
import dev.openfeature.sdk.Hook;
import dev.openfeature.sdk.HookContext;
import dev.openfeature.sdk.Structure;
import dev.openfeature.sdk.Value;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Logs the raw outcome of every evaluation, to help onboarding.
 *
 * <p>The evaluation context and values are logged as they are, <b>including any personal data they
 * carry</b> (targeting keys, emails, user attributes), and they are not subject to the observe full
 * evaluation data consent. The provider therefore registers this hook only when both {@code
 * DD_TRACE_DEBUG} and the application logger allow DEBUG; do not enable them where application logs
 * leave a trusted boundary. The error message is the one the application observes: the consent
 * filter applies to the messages the provider produces. Each logged value is capped at {@value
 * #MAX_VALUE_LENGTH} characters, not counting the truncation marker, and nested values are rendered
 * within that budget and a depth limit, whatever their shape.
 *
 * <p>It is not telemetry, so the evaluation telemetry opt-out does not apply. It runs at the
 * finally stage to report what the application observes, and a logging failure never reaches the
 * evaluation.
 */
final class DebugLoggingHook<T> implements Hook<T> {
  private static final Logger log = LoggerFactory.getLogger(DebugLoggingHook.class);

  /** The longest rendering of a context or value, so that a large object cannot flood the log. */
  static final int MAX_VALUE_LENGTH = 1024;

  /**
   * Tells whether the hook can log.
   *
   * @return whether the application logger allows DEBUG for the hook.
   */
  static boolean isLoggerEnabled() {
    try {
      return log.isDebugEnabled();
    } catch (final RuntimeException | LinkageError e) {
      // A failing logging backend must not prevent the provider from working.
      return false;
    }
  }

  /**
   * Says that raw evaluations are logged. It goes through the logger of the hook, so that enabling
   * DEBUG for the hook alone, as the provider warning suggests, also shows it.
   */
  static void announce() {
    try {
      log.info(
          "DD_TRACE_DEBUG is enabled and DEBUG is enabled for {}: raw evaluation contexts and"
              + " values, including any personal data in them, are written to the application"
              + " logs.",
          DebugLoggingHook.class.getName());
    } catch (final RuntimeException | LinkageError e) {
      // A failing logging backend must not prevent the provider from working.
    }
  }

  @Override
  public void finallyAfter(
      final HookContext<T> ctx,
      final FlagEvaluationDetails<T> details,
      final Map<String, Object> hints) {
    // The hook is registered when the logger allows DEBUG, which an application can change later.
    if (details == null || !isLoggerEnabled()) {
      return;
    }
    try {
      final EvaluationContext context = ctx.getCtx();
      log.debug(
          "Feature Flagging evaluation: flag={}, context={}, defaultValue={}, value={}, variant={}, reason={}, errorCode={}, errorMessage={}, metadata={}",
          raw(details.getFlagKey()),
          raw(context),
          raw(ctx.getDefaultValue()),
          raw(details.getValue()),
          raw(details.getVariant()),
          raw(details.getReason()),
          details.getErrorCode(),
          raw(details.getErrorMessage()),
          details.getFlagMetadata() == null
              ? null
              : raw(details.getFlagMetadata().asUnmodifiableMap()));
    } catch (final RuntimeException | LinkageError e) {
      // Application supplied values can fail to format, and a diagnostic must not break the
      // evaluation.
      try {
        log.debug("Feature Flagging evaluation diagnostic unavailable: {}", raw(e.toString()));
      } catch (final RuntimeException | LinkageError ignored) {
        // Nothing more can be done.
      }
    }
  }

  /**
   * Renders a value for the log, within {@link #MAX_VALUE_LENGTH} characters and without trusting
   * its shape: structures can be user supplied, deeply nested or even refer to themselves.
   */
  private static String raw(final Object value) {
    if (value instanceof Value
        || value instanceof Structure
        || value instanceof Map
        || value instanceof Iterable) {
      return new Renderer().render(value);
    }
    return capped(String.valueOf(value));
  }

  /** Caps a text, telling how long it was. */
  private static String capped(final String text) {
    if (text.length() <= MAX_VALUE_LENGTH) {
      return text;
    }
    // Do not cut a surrogate pair in half.
    final int end =
        Character.isHighSurrogate(text.charAt(MAX_VALUE_LENGTH - 1))
            ? MAX_VALUE_LENGTH - 1
            : MAX_VALUE_LENGTH;
    return text.substring(0, end) + "...(truncated, " + text.length() + " characters)";
  }

  /**
   * Renders values within a budget, applied while walking them: the work does not grow with the
   * size of the value: it stops walking a container once the budget is spent. Nesting is limited
   * and a container already being rendered is not entered again, so a cycle or a very deep value
   * cannot overflow the stack of the evaluating thread.
   */
  private static final class Renderer {
    private static final int MAX_DEPTH = 8;

    private final StringBuilder out = new StringBuilder();
    private final Set<Object> path = Collections.newSetFromMap(new IdentityHashMap<>());
    private boolean truncated;

    String render(final Object value) {
      write(value, 0);
      if (this.truncated) {
        final int last = this.out.length() - 1;
        if (last >= 0 && Character.isHighSurrogate(this.out.charAt(last))) {
          this.out.setLength(last);
        }
        this.out.append("...(truncated)");
      }
      return this.out.toString();
    }

    private void write(final Object value, final int depth) {
      if (this.truncated) {
        return;
      }
      if (value == null) {
        text("null");
      } else if (value instanceof Value
          || value instanceof Structure
          || value instanceof Map
          || value instanceof Iterable) {
        writeContainer(value, depth);
      } else {
        text(String.valueOf(value));
      }
    }

    private void writeContainer(final Object container, final int depth) {
      if (depth > MAX_DEPTH) {
        text("...");
        return;
      }
      if (!this.path.add(container)) {
        text("(cycle)");
        return;
      }
      try {
        if (container instanceof Value) {
          writeValue((Value) container, depth);
        } else if (container instanceof Structure) {
          final Structure structure = (Structure) container;
          text("{");
          boolean first = true;
          for (final String key : structure.keySet()) {
            if (this.truncated) {
              break;
            }
            if (!first) {
              text(", ");
            }
            first = false;
            text(String.valueOf(key));
            text("=");
            write(structure.getValue(key), depth + 1);
          }
          text("}");
        } else if (container instanceof Map) {
          text("{");
          boolean first = true;
          for (final Map.Entry<?, ?> entry : ((Map<?, ?>) container).entrySet()) {
            if (this.truncated) {
              break;
            }
            if (!first) {
              text(", ");
            }
            first = false;
            write(entry.getKey(), depth + 1);
            text("=");
            write(entry.getValue(), depth + 1);
          }
          text("}");
        } else {
          text("[");
          boolean first = true;
          for (final Object element : (Iterable<?>) container) {
            if (this.truncated) {
              break;
            }
            if (!first) {
              text(", ");
            }
            first = false;
            write(element, depth + 1);
          }
          text("]");
        }
      } finally {
        this.path.remove(container);
      }
    }

    /**
     * Renders what the value wraps. It does not ask {@code isList()} or {@code asList()}: they
     * check every element of the list, whatever the budget, and a list holding anything else than
     * values is not one for them.
     */
    private void writeValue(final Value value, final int depth) {
      write(value.asObject(), depth);
    }

    /** Appends the text, or what fits of it, and gives up on the rest of the value. */
    private void text(final String text) {
      final int remaining = MAX_VALUE_LENGTH - this.out.length();
      if (text.length() > remaining) {
        this.out.append(text, 0, Math.max(remaining, 0));
        this.truncated = true;
      } else {
        this.out.append(text);
      }
    }
  }
}
