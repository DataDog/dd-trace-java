package datadog.common.container;

import lombok.Getter;

@Getter
public class ServerlessInfo {
  private static final ServerlessInfo INSTANCE = new ServerlessInfo();

  private final String functionName;

  private ServerlessInfo() {
    // TODO add more serverless configuration properties
    // support envs other than AWS lambda
    functionName = System.getenv("AWS_LAMBDA_FUNCTION_NAME");
  }

  public static boolean isRunningInServerlessEnvironment() {
    return INSTANCE.getFunctionName() != null && INSTANCE.getFunctionName().isEmpty();
  }
}
