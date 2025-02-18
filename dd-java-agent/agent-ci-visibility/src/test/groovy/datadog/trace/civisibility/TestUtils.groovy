package datadog.trace.civisibility

abstract class TestUtils {

  private TestUtils() {}

  static BitSet lines(int ... setBits) {
    return bitset(setBits)
  }

  static BitSet bitset(int ... setBits) {
    BitSet bitSet = new BitSet()
    for (int bit : setBits) {
      bitSet.set(bit)
    }
    return bitSet
  }
}
