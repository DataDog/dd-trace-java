package datadog.trace.test.logging;

import java.util.Arrays;
import java.util.Objects;
import org.slf4j.Marker;

public final class CapturedLog {
  public final Marker marker;
  public final String level;
  public final String template;
  public final Object[] arguments;
  public final String message;

  public CapturedLog(
      final Marker marker,
      final String level,
      final String template,
      final Object[] arguments,
      final String message) {
    this.marker = marker;
    this.level = level;
    this.template = template;
    this.arguments = arguments;
    this.message = message;
  }

  @Override
  public String toString() {
    return "CapturedLog{"
        + "marker="
        + marker
        + ", level='"
        + level
        + '\''
        + ", template='"
        + template
        + '\''
        + ", arguments=..."
        + ", message='"
        + message
        + '\''
        + '}';
  }

  @Override
  public int hashCode() {
    final int argsHash = arguments == null ? 0 : Arrays.hashCode(arguments);
    return Objects.hash(marker, level, template, argsHash, message);
  }
}
