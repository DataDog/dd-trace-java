package datadog.trace.bootstrap.debugger;

import java.util.List;
import java.util.Objects;

/** Stores a collection of DiagnosticMessages related to a probe */
public final class ProbeDiagnostic {
  private final String probeId;
  private final List<DiagnosticMessage> messages;

  public ProbeDiagnostic(String probeId, List<DiagnosticMessage> messages) {
    this.probeId = probeId;
    this.messages = messages;
  }

  public String getProbeId() {
    return probeId;
  }

  public List<DiagnosticMessage> getMessages() {
    return messages;
  }

  @Override
  public String toString() {
    return "ProbeDiagnostic{" + "probeId='" + probeId + '\'' + ", messages=" + messages + '}';
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    ProbeDiagnostic that = (ProbeDiagnostic) o;
    return Objects.equals(probeId, that.probeId) && Objects.equals(messages, that.messages);
  }

  @Override
  public int hashCode() {
    return Objects.hash(probeId, messages);
  }
}
