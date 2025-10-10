package datadog.trace.api.telemetry;

import javax.annotation.Nullable;

public enum RuleType {
  LFI(Type.LFI),
  SQL_INJECTION(Type.SQL_INJECTION),
  SSRF_REQUEST(Type.SSRF, Variant.REQUEST),
  SSRF_RESPONSE(Type.SSRF, Variant.RESPONSE),
  SHELL_INJECTION(Type.COMMAND_INJECTION, Variant.SHELL),
  COMMAND_INJECTION(Type.COMMAND_INJECTION, Variant.EXEC);

  public final Type type;
  @Nullable public final Variant variant;
  private static final int numValues = RuleType.values().length;

  RuleType(Type type) {
    this(type, null);
  }

  RuleType(Type type, @Nullable Variant variant) {
    this.type = type;
    this.variant = variant;
  }

  public static int getNumValues() {
    return numValues;
  }

  public enum Type {
    LFI("lfi"),
    SQL_INJECTION("sql_injection"),
    SSRF("ssrf"),
    COMMAND_INJECTION("command_injection");

    public final String name;

    Type(String name) {
      this.name = name;
    }

    @Override
    public String toString() {
      return name;
    }
  }

  public enum Variant {
    SHELL("shell"),
    EXEC("exec"),
    REQUEST("request"),
    RESPONSE("response");

    public final String name;

    Variant(String name) {
      this.name = name;
    }

    @Override
    public String toString() {
      return name;
    }
  }
}
