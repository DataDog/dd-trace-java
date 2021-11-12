package datadog.common.container;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.File;

public class ServerlessInfo {
  private static final String AWS_FUNCTION_VARIABLE = "AWS_LAMBDA_FUNCTION_NAME";
  private static final String EXTENSION_PATH = "/opt/extensions/datadog-agent";
  private static final ServerlessInfo INSTANCE = new ServerlessInfo();

  private final String functionName;
  private final boolean hasExtension;

  private ServerlessInfo(final String extensionPath) {
    this.functionName = System.getenv(AWS_FUNCTION_VARIABLE);
    if (null == extensionPath) {
      this.hasExtension = false;
    } else {
      File f = new File(extensionPath);
      this.hasExtension = (f.exists() && !f.isDirectory());
    }
  }

  @SuppressFBWarnings("DMI_HARDCODED_ABSOLUTE_FILENAME")
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
    return hasExtension;
  }

  public String getFunctionName() {
    return functionName;
  }
}
