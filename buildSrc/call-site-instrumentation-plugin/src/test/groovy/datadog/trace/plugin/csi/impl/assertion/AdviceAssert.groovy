package datadog.trace.plugin.csi.impl.assertion

class AdviceAssert {
  protected String owner
  protected String method
  protected String descriptor
  protected Collection<String> statements

  void pointcut(String owner, String method, String descriptor) {
    assert owner == this.owner
    assert method == this.method
    assert descriptor == this.descriptor
  }

  void statements(String... values) {
    assert values.toList() == statements
  }
}
