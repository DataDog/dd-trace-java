package datadog.crashtracking.dto;

public final class ProcInfo {
  public final int pid;

  public ProcInfo(int pid) {
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
    return pid == procInfo.pid;
  }

  @Override
  public int hashCode() {
    return pid;
  }
}
