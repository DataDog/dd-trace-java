package datadog.trace.bootstrap.debugger;

import java.util.Collections;
import java.util.List;
import java.util.Objects;
import datadog.trace.util.HashingUtils;

/** Probe location information used in ProbeDetails class */
public class ProbeLocation {
  public static final ProbeLocation UNKNOWN =
      new ProbeLocation("UNKNOWN", "UNKNOWN", "UNKNOWN", Collections.emptyList());

  private final String type; // class
  private final String method;
  private final String file;
  private final List<String> lines;

  public ProbeLocation(String type, String method, String file, List<String> lines) {
    this.type = type;
    this.method = method;
    this.file = file;
    this.lines = lines;
  }

  public String getType() {
    return type;
  }

  public String getMethod() {
    return method;
  }

  public String getFile() {
    return file;
  }

  public List<String> getLines() {
    return lines;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    ProbeLocation that = (ProbeLocation) o;
    return Objects.equals(type, that.type)
        && Objects.equals(method, that.method)
        && Objects.equals(file, that.file)
        && Objects.equals(lines, that.lines);
  }

  @Override
  public int hashCode() {
    return HashingUtils.hash(type, method, file, lines);
  }

  @Override
  public String toString() {
    return "ProbeLocation{"
        + "type='"
        + type
        + '\''
        + ", method='"
        + method
        + '\''
        + ", file='"
        + file
        + '\''
        + ", lines="
        + lines
        + '}';
  }
}
