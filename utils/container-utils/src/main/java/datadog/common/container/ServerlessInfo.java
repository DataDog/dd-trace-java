package datadog.common.container;

import java.io.File;

public class ServerlessInfo {
  private static final String AWS_FUNCTION_VARIABLE = "AWS_LAMBDA_FUNCTION_NAME";
  private static final String EXTENSION_PATH = "/opt/extensions/datadog-agent";
  private static final ServerlessInfo INSTANCE = new ServerlessInfo();

  private final String functionName;
  private final String extensionPath;

  private ServerlessInfo(final String extensionPath) {
    this.extensionPath = extensionPath;
    this.functionName = System.getenv(AWS_FUNCTION_VARIABLE);
  }

  public ServerlessInfo() {
    // TODO add more serverless configuration properties
    // support envs other than AWS lambda
    this(EXTENSION_PATH);
  }

  public static ServerlessInfo get() {
    return INSTANCE;
  }

  public boolean isRunningInServerlessEnvironment() {
    return functionName != null && !functionName.isEmpty();
  }

  public boolean hasExtension() {
    if (null == this.extensionPath) {
      return false;
    }
    File f = new File(this.extensionPath);
    return (f.exists() && !f.isDirectory());
  }

  public String getFunctionName() {
    return functionName;
  }
}
