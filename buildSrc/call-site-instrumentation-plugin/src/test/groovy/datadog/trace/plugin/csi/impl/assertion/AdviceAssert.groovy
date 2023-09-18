package datadog.trace.plugin.csi.impl.assertion

class AdviceAssert {
  protected String owner
  protected String method
  protected String descriptor
  protected StatementsAssert statements

  void pointcut(String owner, String method, String descriptor) {
    assert owner == this.owner
    assert method == this.method
    assert descriptor == this.descriptor
  }

  void statements(String ...statements) {
    this.statements.asString(statements)
  }

  void statements(@DelegatesTo(StatementsAssert) Closure closure) {
    closure.delegate = statements
    closure(statements)
  }
}
