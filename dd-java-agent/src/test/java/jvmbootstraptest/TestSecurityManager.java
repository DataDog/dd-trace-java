package jvmbootstraptest;

import java.io.FilePermission;
import java.lang.reflect.ReflectPermission;
import java.net.SocketPermission;
import java.util.PropertyPermission;

/**
 * Goal is to provide the minimum set of permissions needed for all aspects of dd-java-agent to
 * function normally.
 */
public class TestSecurityManager extends CustomSecurityManager {
  public static final class NoEnvAccess extends TestSecurityManager {
    @Override
    protected final boolean checkRuntimeEnvironmentAccess(
        RuntimePermission perm, Object ctx, String envVar) {
      return false;
    }
  }

  public static final class MinimalPropertyAccess extends TestSecurityManager {
    @Override
    protected boolean checkPropertyReadPermission(
        PropertyPermission perm, Object ctx, String property) {
      return minimalCheckPropertyReadPermission(perm, ctx, property);
    }

    @Override
    protected boolean checkPropertyWritePermission(
        PropertyPermission perm, Object ctx, String property) {
      return false;
    }
  }

  public static final class NoProcessExecution extends TestSecurityManager {
    @Override
    protected boolean checkFileExecutePermission(FilePermission perm, Object ctx, String filePath) {
      return false;
    }
  }

  public static final class NoNetworkAccess extends TestSecurityManager {
    @Override
    protected boolean checkSocketConnect(SocketPermission perm, Object ctx, String host, int port) {
      return false;
    }

    @Override
    protected boolean checkSocketResolve(SocketPermission perm, Object ctx, String host) {
      return false;
    }
  }

  @Override
  protected boolean checkPropertyReadPermission(
      PropertyPermission perm, Object ctx, String property) {
    return isDatadogProperty(property)
        || isOkHttpProperty(property)
        || isSlf4jProperty(property)
        || isByteBuddyProperty(property)
        || isJcToolsProperty(property)
        || super.checkPropertyReadPermission(perm, ctx, property);
  }

  @Override
  protected boolean checkPropertyWritePermission(
      PropertyPermission perm, Object ctx, String property) {
    return isDatadogProperty(property)
        || isOkHttpProperty(property)
        || isByteBuddyProperty(property)
        || super.checkPropertyWritePermission(perm, ctx, property);
  }

  protected static final boolean isDatadogProperty(String propertyName) {
    return propertyName.startsWith("datadog.") || propertyName.startsWith("dd.");
  }

  protected static final boolean isSlf4jProperty(String propertyName) {
    return propertyName.startsWith("slf4j.");
  }

  protected static final boolean isByteBuddyProperty(String propertyName) {
    return propertyName.startsWith("net.bytebuddy.");
  }

  protected static final boolean isJcToolsProperty(String propertyName) {
    return propertyName.startsWith("jctools.");
  }

  protected static final boolean isOkHttpProperty(String propertyName) {
    return propertyName.startsWith("okhttp.");
  }

  @Override
  protected boolean checkRuntimeEnvironmentAccess(
      RuntimePermission perm, Object ctx, String envVar) {
    if (isDatadogEnvVar(envVar)) return true;

    switch (envVar) {
      // jboss sniffing?
      case "JBOSS_HOME":
        return true;

      // environment capture?
      case "WEBSITE_SITE_NAME":
        return true;

      // AWS properties used during bootstrapping?
      case "AWS_LAMBDA_INITIALIZATION_TYPE":
      case "_HANDLER":
      case "AWS_LAMBDA_FUNCTION_NAME":
        return true;
    }

    return super.checkRuntimeEnvironmentAccess(perm, ctx, envVar);
  }

  protected static final boolean isDatadogEnvVar(String envVar) {
    return envVar.startsWith("DATADOG_") || envVar.startsWith("DD_");
  }

  @Override
  protected boolean checkReflectPermission(ReflectPermission perm, Object ctx, String permName) {
    // override to allow suppress of access checks
    return true;
  }

  @Override
  protected boolean checkRuntimeClassLoaderModification(
      RuntimePermission perm, Object ctx, String permName) {
    // override to allow ClassLoader creation & set context ClassLoader
    return true;
  }

  @Override
  protected boolean checkRuntimeMBeanProviderAccess(RuntimePermission perm, Object ctx) {
    return true;
  }

  @Override
  protected boolean checkRuntimeSystemModuleAccess(RuntimePermission perm, Object ctx) {
    // slf4j fails to initialize without this; what else?
    return true;
  }

  @Override
  protected boolean checkRuntimeManageProcess(RuntimePermission perm, Object ctx) {
    return true;
  }

  @Override
  protected boolean checkRuntimeContextClassLoader(RuntimePermission perm, Object ctx) {
    return true;
  }

  @Override
  protected boolean checkRuntimeShutdownHooks(RuntimePermission perm, Object ctx) {
    return true;
  }

  @Override
  protected boolean checkFileReadPermission(FilePermission perm, Object ctx, String filePath) {
    switch (filePath) {
      // agent socket communication
      case "/var/run/datadog/apm.socket":
        return true;

      // agent sniffing?
      case "/opt/extensions/datadog-agent":
        return true;

      // ContainerInfo
      case "/proc/self/cgroup":
        return true;
    }

    // version info
    if (filePath.endsWith("/dd-java-agent.version")) return true;

    if (filePath.endsWith("/simplelogger.properties")) return true;

    return super.checkFileReadPermission(perm, ctx, filePath);
  }

  @Override
  protected boolean checkFileExecutePermission(FilePermission perm, Object ctx, String filePath) {
    return true;
  }

  @Override
  protected boolean checkRuntimeFileSystemAccess(
      RuntimePermission perm, Object ctx, String permission) {
    // used by ContainerInfo
    return true;
  }

  @Override
  protected boolean checkOtherRuntimePermission(
      RuntimePermission perm, Object ctx, String permName) {
    switch (permName) {
      case "net.bytebuddy.createJavaDispatcher":
        return true;

      default:
        return false;
    }
  }

  @Override
  protected boolean checkSocketResolve(SocketPermission perm, Object ctx, String host) {
    return true;
  }

  @Override
  protected boolean checkSocketConnect(SocketPermission perm, Object ctx, String host, int port) {
    return true;
  }
}
