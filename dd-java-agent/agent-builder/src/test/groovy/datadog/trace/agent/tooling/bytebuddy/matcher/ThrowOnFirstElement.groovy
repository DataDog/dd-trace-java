package datadog.trace.agent.tooling.bytebuddy.matcher

class ThrowOnFirstElement implements Iterator<Object> {

  int i = 0

  @Override
  boolean hasNext() {
    return i++ < 1
  }

  @Override
  Object next() {
    throw new Exception("iteration exception")
  }
}
