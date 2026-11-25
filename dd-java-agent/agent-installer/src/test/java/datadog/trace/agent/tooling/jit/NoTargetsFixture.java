package datadog.trace.agent.tooling.jit;

/** Has no calls to rewrite. */
public class NoTargetsFixture {
  public static String concat(String a, String b) {
    return a + b;
  }
}
