package datadog.trace.api.iast;

public abstract class PropagationTypes {

  private PropagationTypes() {}

  public static final String STRING = "STRING";
  public static final String JSON = "JSON";
  public static final String URL = "URL";
  public static final String COOKIE = "COOKIE";
  public static final String BODY = "BODY";
  public static final String URI = "URI";
}
