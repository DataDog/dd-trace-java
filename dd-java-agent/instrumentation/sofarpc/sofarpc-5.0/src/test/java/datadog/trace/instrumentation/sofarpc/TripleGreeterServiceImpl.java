package datadog.trace.instrumentation.sofarpc;

public class TripleGreeterServiceImpl implements TripleGreeterService {
  @Override
  public String sayHello(String name) {
    return "Hello, " + name;
  }
}
