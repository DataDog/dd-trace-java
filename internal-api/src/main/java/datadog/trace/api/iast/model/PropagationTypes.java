package datadog.trace.api.iast.model;

public abstract class PropagationTypes {

  private PropagationTypes() {}

  public static final String STRING = "STRING";
  public static final String JSON = "JSON";
  public static final String URL = "URL";
  public static final String COOKIE = "COOKIE";
}
