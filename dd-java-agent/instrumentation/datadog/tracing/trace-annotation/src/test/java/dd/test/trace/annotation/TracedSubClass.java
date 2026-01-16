package dd.test.trace.annotation;

public class TracedSubClass extends TracedSuperClass implements TracedInterface {
  @Override
  public String testTracedInterfaceMethod() {
    return "Hello from implemented interface method";
  }

  @Override
  public String testOverriddenTracedDefaultMethod() {
    return "Hello from overridden default method";
  }

  @Override
  public String testTracedAbstractMethod() {
    return "Hello from implemented abstract method";
  }

  @Override
  public String testOverriddenTracedSuperMethod() {
    return "Hello from overridden super method";
  }
}
