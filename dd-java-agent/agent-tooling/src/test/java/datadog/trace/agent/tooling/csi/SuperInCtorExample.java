package datadog.trace.agent.tooling.csi;

import java.io.StringReader;

public class SuperInCtorExample extends StringReader {

  public SuperInCtorExample(String s) {
    super(s + new StringReader(s + "Test" + new StringBuilder("another test")));
  }
}
