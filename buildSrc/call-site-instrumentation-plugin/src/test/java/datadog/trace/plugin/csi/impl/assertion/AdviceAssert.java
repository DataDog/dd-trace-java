package datadog.trace.plugin.csi.impl.assertion;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

public class AdviceAssert {
  protected String type;
  protected String owner;
  protected String method;
  protected String descriptor;
  protected List<String> statements;

  public AdviceAssert(
      String type, String owner, String method, String descriptor, List<String> statements) {
    this.type = type;
    this.owner = owner;
    this.method = method;
    this.descriptor = descriptor;
    this.statements = statements;
  }

  public void type(String type) {
    assertEquals(type, this.type);
  }

  public void pointcut(String owner, String method, String descriptor) {
    assertEquals(owner, this.owner);
    assertEquals(method, this.method);
    assertEquals(descriptor, this.descriptor);
  }

  public void statements(String... values) {
    assertArrayEquals(values, statements.toArray(new String[0]));
  }
}
