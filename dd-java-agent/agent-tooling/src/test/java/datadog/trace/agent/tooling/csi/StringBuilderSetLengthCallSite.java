package datadog.trace.agent.tooling.csi;

public class StringBuilderSetLengthCallSite {

  public static volatile Object[] LAST_CALL;

  public static void after(final StringBuilder builder, int length) {
    LAST_CALL = new Object[] {builder, length};
  }
}
