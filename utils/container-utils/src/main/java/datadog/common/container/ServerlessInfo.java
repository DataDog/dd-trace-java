package datadog.common.container;

import lombok.Getter;

@Getter
public class ServerlessInfo {
  private static final String AWS_FUNCTION_VARIABLE = "AWS_LAMBDA_FUNCTION_NAME";
  private static final ServerlessInfo INSTANCE = new ServerlessInfo();

  private final String functionName;

  public ServerlessInfo() {
    // TODO add more serverless configuration properties
    // support envs other than AWS lambda
    this.functionName = System.getenv(AWS_FUNCTION_VARIABLE);
  }

  public static ServerlessInfo get() {
    return INSTANCE;
  }

  public boolean isRunningInServerlessEnvironment() {
    return functionName != null && !functionName.isEmpty();
  }
}
