import datadog.trace.api.Trace;

public class DummyContext {
  final Resource.Test1 fakeProxy = new Resource.Test1();

  @Trace
  public String getValue() {
    return fakeProxy.hello("world");
  }
}
