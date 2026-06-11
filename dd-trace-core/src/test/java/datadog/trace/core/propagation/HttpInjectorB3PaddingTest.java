package datadog.trace.core.propagation;

class HttpInjectorB3PaddingTest extends HttpInjectorTest {
  @Override
  protected boolean tracePropagationB3Padding() {
    return true;
  }
}
