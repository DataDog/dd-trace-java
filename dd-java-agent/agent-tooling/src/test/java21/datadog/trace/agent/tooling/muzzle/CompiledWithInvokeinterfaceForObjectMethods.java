package datadog.trace.agent.tooling.muzzle;

public class CompiledWithInvokeinterfaceForObjectMethods {
  interface DatadogInterface {}

  public void doSomething(DatadogInterface itf) {
    itf.hashCode();
    itf.equals(new Object());
    itf.getClass();
  }
}
