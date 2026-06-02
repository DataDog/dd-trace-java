package datadog.trace.core.propagation;

class B3HttpInjectorPaddedTest extends B3HttpInjectorTest {
  @Override
  protected boolean tracePropagationB3Padding() {
    return true;
  }
}
