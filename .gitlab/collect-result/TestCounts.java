record TestCounts(long tests, long failures, long errors, long skipped) {
  static final TestCounts ZERO = new TestCounts(0, 0, 0, 0);

  TestCounts plus(TestCounts other) {
    return new TestCounts(
        tests + other.tests,
        failures + other.failures,
        errors + other.errors,
        skipped + other.skipped);
  }

  String asLine() {
    return tests + " " + failures + " " + errors + " " + skipped;
  }
}
