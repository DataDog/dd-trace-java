package stackstate.trace.agent.integration.classloading;

import stackstate.trace.api.Trace;

class ClassToInstrument {
  @Trace
  public static void someMethod() {}
}
