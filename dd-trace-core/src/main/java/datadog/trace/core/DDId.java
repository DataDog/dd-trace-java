package datadog.trace.core;

import java.util.concurrent.ThreadLocalRandom;

public class DDId {

  public static DDId ZERO = DDId.from(0);

  public static DDId from(long id) {
    return new DDId(id);
  }

  private final long id;
  private String str;
  private String hex;

  private DDId(long id) {
    this.id = id;
  }

  public DDId() {
    // It is **extremely** unlikely to generate the value "0" but we still need to handle that
    // case
    long id;
    do {
      id = ThreadLocalRandom.current().nextLong(Long.MAX_VALUE);
    } while (id == 0);
    this.id = id;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    DDId ddId = (DDId) o;
    return this.id == ddId.id;
  }

  @Override
  public int hashCode() {
    return (int) this.id;
  }

  @Override
  public String toString() {
    if (this.str == null) {
      String str = Long.toString(this.id);
      this.str = str;
      return str;
    }
    return this.str;
  }

  public String toHexString() {
    if (this.hex == null) {
      String hex = Long.toHexString(this.id);
      this.hex = hex;
      return hex;
    }
    return this.hex;
  }

  public long toLong() {
    return this.id;
  }
}
