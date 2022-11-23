package datadog.trace.instrumentation.thrift;

import org.apache.thrift.ProcessFunction;

import java.util.Map;

public class Context extends AbstractContext {
  private Map<String, ProcessFunction> processMapView;

  public Context(Map<String, ProcessFunction> processMapView) {
    this.processMapView = processMapView;
  }

  @Override
  public String getArguments() {
    return
        processMapView.get(methodName).getEmptyArgsInstance().toString();
  }

  @Override
  public String getOperatorName() {
    return processMapView.get(methodName).getClass().getName();
  }

}
