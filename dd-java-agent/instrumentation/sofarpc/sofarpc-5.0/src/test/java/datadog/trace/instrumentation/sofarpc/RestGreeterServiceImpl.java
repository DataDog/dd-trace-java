package datadog.trace.instrumentation.sofarpc;

public class RestGreeterServiceImpl implements RestGreeterService {
  @Override
  public String sayHello(String name) {
    return "Hello, " + name;
  }
}
