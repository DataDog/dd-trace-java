package datadog.trace.agent.tooling.csi;

import java.io.StringReader;

public class SuperInCtorExample extends StringReader {

  public SuperInCtorExample(String s) {
    // triggers APPSEC-55918
    super(s + new StringReader(s + "Test" + new StringBuilder("another test")));
  }

  public SuperInCtorExample(StringBuilder s) {
    super(s.toString());
    // triggers APPSEC-58131
    if (s.length() == 0) {
      throw new IllegalArgumentException();
    }
  }
}
