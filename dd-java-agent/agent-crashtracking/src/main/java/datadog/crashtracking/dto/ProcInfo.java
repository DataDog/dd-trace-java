package datadog.crashtracking.dto;

import java.util.Objects;

public final class ProcInfo {
  public final String pid;

  public ProcInfo(String pid) {
    this.pid = pid;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    ProcInfo procInfo = (ProcInfo) o;
    return Objects.equals(pid, procInfo.pid);
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(pid);
  }
}
