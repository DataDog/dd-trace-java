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
    if (processMapView==null){
      return null;
    }
    ProcessFunction function = processMapView.get(methodName);
    if (function==null){
      return null;
    }
    return function.getEmptyArgsInstance().toString();
  }

  @Override
  public String getOperatorName() {
    if (processMapView==null){
      return null;
    }
    ProcessFunction function = processMapView.get(methodName);
    if (function==null){
      return methodName;
    }
    return function.getClass().getName();
  }

}
