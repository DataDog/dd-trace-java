package datadog.trace.util;

/** Shared test entry types for {@link HashtableTest}, {@link HashtableD1Test}, and friends. */
final class HashtableTestEntries {
  private HashtableTestEntries() {}

  static final class StringIntEntry extends Hashtable.D1.Entry<String> {
    int value;

    StringIntEntry(String key, int value) {
      super(key);
      this.value = value;
    }
  }

  /** Key whose hashCode is fully controllable, to force chain collisions deterministically. */
  static final class CollidingKey {
    final String label;
    final int hash;

    CollidingKey(String label, int hash) {
      this.label = label;
      this.hash = hash;
    }

    @Override
    public int hashCode() {
      return hash;
    }

    @Override
    public boolean equals(Object o) {
      if (!(o instanceof CollidingKey)) {
        return false;
      }
      CollidingKey that = (CollidingKey) o;
      return hash == that.hash && label.equals(that.label);
    }

    @Override
    public String toString() {
      return "CollidingKey(" + label + ", " + hash + ")";
    }
  }

  static final class CollidingKeyEntry extends Hashtable.D1.Entry<CollidingKey> {
    int value;

    CollidingKeyEntry(CollidingKey key, int value) {
      super(key);
      this.value = value;
    }
  }
}
