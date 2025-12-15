package datadog.trace.bootstrap.debugger;

import datadog.trace.util.RandomUtils;

public class ProbeId {
  private static final String ID_SEPARATOR = ":";

  private final String id;
  private final int version;
  private final String encoded; // store as string uuid:version

  // decode a probe id from a string with format uuid:version
  public static ProbeId from(String encodedId) {
    int idx = encodedId.indexOf(ID_SEPARATOR);
    if (idx == -1) {
      throw new IllegalArgumentException("Invalid probe id: " + encodedId);
    }
    return new ProbeId(
        encodedId.substring(0, idx), Integer.parseInt(encodedId.substring(idx + 1)), encodedId);
  }

  public ProbeId(String id, int version) {
    this.id = id;
    this.version = version;
    this.encoded = id + ID_SEPARATOR + version;
  }

  private ProbeId(String id, int version, String encoded) {
    this.id = id;
    this.version = version;
    this.encoded = encoded;
  }

  public static ProbeId newId() {
    return new ProbeId(RandomUtils.randomUUID().toString(), 0);
  }

  public String getId() {
    return id;
  }

  public int getVersion() {
    return version;
  }

  /**
   * @return the encoded id as a string with format uuid:version
   */
  public String getEncodedId() {
    return encoded;
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
