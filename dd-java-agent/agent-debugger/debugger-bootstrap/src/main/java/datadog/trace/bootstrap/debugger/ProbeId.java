package datadog.trace.bootstrap.debugger;

public class ProbeId {
  private final String id;
  private final int version;

  public ProbeId(String id, int version) {
    this.id = id;
    this.version = version;
  }

  public String getId() {
    return id;
  }

  public int getVersion() {
    return version;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;

    ProbeId probeId = (ProbeId) o;

    if (version != probeId.version) return false;
    return id.equals(probeId.id);
  }

  @Override
  public int hashCode() {
    int result = id.hashCode();
    result = 31 * result + version;
    return result;
  }

  @Override
  public String toString() {
    return "ProbeId{" + "id='" + id + '\'' + ", version=" + version + '}';
  }
}
