package jvmbootstraptest;

import java.io.FilePermission;
import java.lang.reflect.ReflectPermission;
import java.net.NetPermission;
import java.net.SocketPermission;
import java.security.Permission;
import java.security.SecurityPermission;
import java.util.PropertyPermission;
import javax.management.MBeanServerPermission;

/**
 * Custom SecurityManager that aims to grant all permissions needed for a JVM to run in minimal
 * fashion.
 *
 * <p>This security manager grants access to java packages, java properties, and libraries bundled
 * with the JVM, but blocks everything else.
 */
public class CustomSecurityManager extends SecurityManager {
  public static final boolean DEBUG = true;

  protected CustomSecurityManager() {}

  @Override
  public final void checkPermission(Permission perm) {
    checkPermission(perm, null);
  }

  @Override
  public final void checkPermission(Permission perm, Object ctx) {
    boolean allow = false;

    if (perm instanceof RuntimePermission) {
      allow = checkRuntimePermission((RuntimePermission) perm, ctx);
    } else if (perm instanceof NetPermission) {
      allow = checkNetPermission((NetPermission) perm, ctx);
    } else if (perm instanceof ReflectPermission) {
      allow = checkReflectPermission((ReflectPermission) perm, ctx);
    } else if (perm instanceof SecurityPermission) {
      allow = checkSecurityPermission((SecurityPermission) perm, ctx);
    } else if (perm instanceof FilePermission) {
      allow = checkFilePermission((FilePermission) perm, ctx);
    } else if (perm instanceof PropertyPermission) {
      allow = checkPropertyPermission((PropertyPermission) perm, ctx);
    } else if (perm instanceof MBeanServerPermission) {
      allow = checkMBeanServerPermission((MBeanServerPermission) perm, ctx);
    } else if (perm instanceof SocketPermission) {
      allow = checkSocketPermission((SocketPermission) perm, ctx);
    }

    if (!allow) {
      if (DEBUG) System.err.println("Blocked: " + perm);

      block(perm);
    }
  }

  final boolean checkRuntimePermission(RuntimePermission perm, Object ctx) {
    return checkRuntimePermission(perm, ctx, perm.getName());
  }

  final boolean checkRuntimePermission(RuntimePermission perm, Object ctx, String name) {
    switch (name) {
      case "modifyThread":
      case "modifyThreadGroup":
        return checkRuntimeThreadAccess(perm, ctx, name);

      case "accessDeclaredMembers":
      case "getProtectionDomain":
        return checkRuntimeClassAccess(perm, ctx, name);

      case "getClassLoader":
        return checkRuntimeClassLoaderAccess(perm, ctx, name);

      case "createClassLoader":
      case "setContextClassLoader":
        return checkRuntimeClassLoaderModification(perm, ctx, name);

      case "accessUserInformation":
        return checkRuntimeUserAccess(perm, ctx);

      case "shutdownHooks":
        return checkRuntimeShutdownHooks(perm, ctx);

      case "fileSystemProvider":
      case "readFileDescriptor":
      case "writeFileDescriptor":
        return checkRuntimeFileSystemAccess(perm, ctx, name);

      case "accessSystemModules":
        return checkRuntimeSystemModuleAccess(perm, ctx);

      case "jdk.internal.perf.Perf.getPerf":
        return checkRuntimePerfAccess(perm, ctx);

      case "sun.management.spi.PlatformMBeanProvider.subclass":
        return checkRuntimeMBeanProviderAccess(perm, ctx);

      case "manageProcess":
        return checkRuntimeManageProcess(perm, ctx);

      case "enableContextClassLoaderOverride":
        return checkRuntimeContextClassLoader(perm, ctx);

      case "setIO":
        return checkRuntimeSetIO(perm, ctx);
    }

    if (name.startsWith("accessClassInPackage.")) {
      String pkg = name.substring("accessClassInPackage.".length());
      return checkRuntimePackageAccess(perm, ctx, pkg);
    } else if (name.startsWith("loadLibrary.")) {
      String library = name.substring("loadLibrary.".length());
      return checkRuntimeLoadLibrary(perm, ctx, library);
    } else if (name.startsWith("getenv.")) {
      String envVar = name.substring("getenv.".length());
      return checkRuntimeEnvironmentAccess(perm, ctx, envVar);
    } else if (name.startsWith("exitVM.")) {
      int exitCode = Integer.parseInt(name.substring("exitVM.".length()));
      return checkRuntimeSystemExit(perm, ctx, exitCode);
    }

    return checkOtherRuntimePermission(perm, ctx, name);
  }

  protected boolean checkRuntimeSystemExit(RuntimePermission perm, Object ctx, int exitCode) {
    return defaultCheckRuntimeSystemExit(perm, ctx, exitCode);
  }

  protected boolean defaultCheckRuntimeSystemExit(
      RuntimePermission perm, Object ctx, int exitCode) {
    return false;
  }

  protected boolean checkRuntimeThreadAccess(RuntimePermission perm, Object ctx, String name) {
    return defaultCheckRuntimeThreadAccess(perm, ctx, name);
  }

  protected final boolean defaultCheckRuntimeThreadAccess(
      RuntimePermission perm, Object ctx, String name) {
    return true;
  }

  protected boolean checkRuntimeUserAccess(RuntimePermission perm, Object ctx) {
    return defaultCheckRuntimeUserAccess(perm, ctx);
  }

  protected final boolean defaultCheckRuntimeUserAccess(RuntimePermission perm, Object ctx) {
    return true;
  }

  protected boolean checkRuntimeEnvironmentAccess(
      RuntimePermission perm, Object ctx, String envVar) {
    return defaultCheckRuntimeEnvironmentAccess(perm, ctx, envVar);
  }

  protected final boolean defaultCheckRuntimeEnvironmentAccess(
      RuntimePermission perm, Object ctx, String envVar) {
    switch (envVar) {
      case "HOSTNAME":
        return true;

      default:
        return false;
    }
  }

  protected boolean checkRuntimePackageAccess(
      RuntimePermission perm, Object ctx, String packageName) {
    return defaultCheckRuntimeThreadAccess(perm, ctx, packageName);
  }

  protected final boolean defaultCheckRuntimePackageAccess(
      RuntimePermission perm, Object ctx, String packageName) {
    return isBuiltinPackage(packageName);
  }

  protected boolean checkRuntimePerfAccess(RuntimePermission perm, Object ctx) {
    return defaultCheckRuntimePerfAccess(perm, ctx);
  }

  protected final boolean defaultCheckRuntimePerfAccess(RuntimePermission perm, Object ctx) {
    return true;
  }

  protected static final boolean isBuiltinPackage(String packageName) {
    return isSunPackage(packageName)
        || isJavaPackage(packageName)
        || isJcpPackage(packageName)
        || isOsSpecificPackage(packageName);
  }

  protected static final boolean isSunPackage(String packageName) {
    return packageName.startsWith("sun.");
  }

  protected static final boolean isJavaPackage(String packageName) {
    return packageName.startsWith("java.");
  }

  protected static final boolean isJcpPackage(String packageName) {
    return packageName.startsWith("org.jcp.");
  }

  protected static final boolean isOsSpecificPackage(String packageName) {
    return isApplePackage(packageName);
  }

  protected static final boolean isApplePackage(String packageName) {
    return packageName.startsWith("apple.");
  }

  protected boolean checkRuntimeLoadLibrary(
      RuntimePermission perm, Object ctx, String libraryName) {
    return defaultCheckRuntimeLoadLibrary(perm, ctx, libraryName);
  }

  protected boolean defaultCheckRuntimeLoadLibrary(
      RuntimePermission perm, Object ctx, String libraryName) {
    return isBuiltinLibrary(libraryName);
  }

  protected static final boolean isBuiltinLibrary(String libraryName) {
    switch (libraryName) {
      case "instrument":
      case "management":
      case "management_ext":
      case "sunec":
      case "net":
      case "extnet":
        return true;

      default:
        return false;
    }
  }

  protected boolean checkRuntimeClassAccess(RuntimePermission perm, Object ctx, String permName) {
    return defaultCheckRuntimeClassAccess(perm, ctx, permName);
  }

  protected final boolean defaultCheckRuntimeClassAccess(
      RuntimePermission perm, Object ctx, String permName) {
    return true;
  }

  protected boolean checkRuntimeClassLoaderAccess(
      RuntimePermission perm, Object ctx, String permName) {
    return defaultCheckRuntimeClassLoaderAccess(perm, ctx, permName);
  }

  protected final boolean defaultCheckRuntimeClassLoaderAccess(
      RuntimePermission perm, Object ctx, String permName) {
    return true;
  }

  protected boolean checkRuntimeClassLoaderModification(
      RuntimePermission perm, Object ctx, String permName) {
    return checkRuntimeClassLoaderModification(perm, ctx, permName);
  }

  protected final boolean defaultCheckRuntimeClassLoaderModification(
      RuntimePermission perm, Object ctx, String permName) {
    return false;
  }

  protected boolean checkRuntimeShutdownHooks(RuntimePermission perm, Object ctx) {
    return defaultCheckRuntimeShutdownHooks(perm, ctx);
  }

  protected final boolean defaultCheckRuntimeShutdownHooks(RuntimePermission perm, Object ctx) {
    return false;
  }

  protected boolean checkRuntimeSystemModuleAccess(RuntimePermission perm, Object ctx) {
    return defaultCheckRuntimeSystemModuleAccess(perm, ctx);
  }

  protected final boolean defaultCheckRuntimeSystemModuleAccess(
      RuntimePermission perm, Object ctx) {
    return false;
  }

  protected boolean checkRuntimeManageProcess(RuntimePermission perm, Object ctx) {
    return defaultCheckRuntimeManageProcess(perm, ctx);
  }

  protected final boolean defaultCheckRuntimeManageProcess(RuntimePermission perm, Object ctx) {
    return false;
  }

  protected boolean checkRuntimeContextClassLoader(RuntimePermission perm, Object ctx) {
    return defaultCheckRuntimeContextClassLoader(perm, ctx);
  }

  protected final boolean defaultCheckRuntimeContextClassLoader(
      RuntimePermission perm, Object ctx) {
    return false;
  }

  protected boolean checkRuntimeSetIO(RuntimePermission perm, Object ctx) {
    return defaultCheckRuntimeSetIO(perm, ctx);
  }

  protected final boolean defaultCheckRuntimeSetIO(RuntimePermission perm, Object ctx) {
    return true;
  }

  protected boolean checkOtherRuntimePermission(
      RuntimePermission perm, Object ctx, String permName) {
    return defaultOtherRuntimePermission(perm, ctx, permName);
  }

  protected final boolean defaultOtherRuntimePermission(
      RuntimePermission perm, Object ctx, String permName) {
    return false;
  }

  protected boolean checkRuntimeFileSystemAccess(
      RuntimePermission perm, Object ctx, String permission) {
    return defaultCheckRuntimeFileSystemAccess(perm, ctx, permission);
  }

  protected boolean defaultCheckRuntimeFileSystemAccess(
      RuntimePermission perm, Object ctx, String permission) {
    return false;
  }

  protected boolean checkFilePermission(FilePermission perm, Object ctx) {
    switch (perm.getActions()) {
      case "read":
        return checkFileReadPermission(perm, ctx, perm.getName());

      case "write":
        return checkFileWritePermission(perm, ctx, perm.getName());

      case "execute":
        return checkFileExecutePermission(perm, ctx, perm.getName());

      default:
        return false;
    }
  }

  protected boolean checkFileReadPermission(FilePermission perm, Object ctx, String filePath) {
    return defaultCheckFileReadPermission(perm, ctx, filePath);
  }

  protected final boolean defaultCheckFileReadPermission(
      FilePermission perm, Object ctx, String filePath) {
    return isJarFile(filePath)
        || isClassFile(filePath)
        || isLibraryFile(filePath)
        || isJreFile(filePath)
        || isMefaInfLookup(filePath)
        || isProcFile(filePath)
        || isDevFile(filePath)
        || isEtcFile(filePath)
        || isTimeZoneDb(filePath)
        || isNetProperties(filePath)
        || isIbmFile(filePath)
        || isOracleFile(filePath);
  }

  protected boolean checkFileWritePermission(FilePermission perm, Object ctx, String filePath) {
    return defaultCheckFileWritePermission(perm, ctx, filePath);
  }

  protected final boolean defaultCheckFileWritePermission(
      FilePermission perm, Object ctx, String filePath) {
    return false;
  }

  protected boolean checkFileExecutePermission(FilePermission perm, Object ctx, String filePath) {
    return defaultCheckFileExecutePermission(perm, ctx, filePath);
  }

  protected final boolean defaultCheckFileExecutePermission(
      FilePermission perm, Object ctx, String filePath) {
    return false;
  }

  protected static final boolean isJarFile(String filePath) {
    return filePath.endsWith(".jar");
  }

  protected static final boolean isClassFile(String filePath) {
    return filePath.endsWith(".class");
  }

  protected static final boolean isIbmFile(String filePath) {
    return filePath.endsWith("/tmp/.com_ibm_tools_attach");
  }

  protected static final boolean isOracleFile(String filePath) {
    return filePath.contains("/oracle8");
  }

  protected static final boolean isLibraryFile(String filePath) {
    return filePath.endsWith(".dylib") || filePath.endsWith(".so") || filePath.endsWith(".dll");
  }

  protected static final boolean isJreFile(String filePath) {
    return filePath.contains("/jre/");
  }

  protected static final boolean isProcFile(String filePath) {
    return filePath.startsWith("/proc/");
  }

  protected static final boolean isDevFile(String filePath) {
    return filePath.startsWith("/dev/");
  }

  protected static final boolean isEtcFile(String filePath) {
    return filePath.startsWith("/etc/");
  }

  protected static final boolean isMefaInfLookup(String filePath) {
    return filePath.contains("/META-INF");
  }

  protected static final boolean isTimeZoneDb(String filePath) {
    return filePath.endsWith("/tzdb.dat");
  }

  protected static final boolean isNetProperties(String filePath) {
    return filePath.endsWith("/net.properties");
  }

  protected boolean checkNetPermission(NetPermission perm, Object ctx) {
    return defaultCheckNetPermission(perm, ctx);
  }

  final boolean defaultCheckNetPermission(NetPermission perm, Object ctx) {
    if (isBuiltinNetPermission(perm)) return true;

    return false;
  }

  protected boolean isBuiltinNetPermission(NetPermission perm) {
    switch (perm.getName()) {
      case "specifyStreamHandler":
      case "getProxySelector":
        return true;

      default:
        return false;
    }
  }

  protected final boolean checkReflectPermission(ReflectPermission perm, Object ctx) {
    return checkReflectPermission(perm, ctx, perm.getName());
  }

  protected boolean checkReflectPermission(ReflectPermission perm, Object ctx, String permName) {
    return defaultCheckReflectPermission(perm, ctx, permName);
  }

  protected final boolean defaultCheckReflectPermission(
      ReflectPermission perm, Object ctx, String permName) {
    switch (permName) {
      case "suppressAccessChecks":
        return false;
    }

    return true;
  }

  final boolean checkSecurityPermission(SecurityPermission perm, Object ctx) {
    String permName = perm.getName();

    if (permName.startsWith("getProperty.")) {
      String propertyName = permName.substring("getProperty.".length());
      return checkSecurityGetPermission(perm, ctx, propertyName);
    } else if (permName.startsWith("putProviderProperty.")) {
      String propertyName = permName.substring("putProviderProperty.".length());
      return checkSecurityPutPermission(perm, ctx, propertyName);
    }

    return false;
  }

  protected boolean checkSecurityGetPermission(
      SecurityPermission perm, Object ctx, String propertyName) {
    return defaultCheckSecurityGetPermission(perm, ctx, propertyName);
  }

  protected boolean defaultCheckSecurityGetPermission(
      SecurityPermission perm, Object ctx, String propertyName) {
    return true;
  }

  protected boolean checkSecurityPutPermission(
      SecurityPermission perm, Object ctx, String propertyName) {
    return defaultCheckSecurityPutPermission(perm, ctx, propertyName);
  }

  protected final boolean defaultCheckSecurityPutPermission(
      SecurityPermission perm, Object ctx, String propertyName) {
    return true;
  }

  final boolean checkPropertyPermission(PropertyPermission perm, Object ctx) {
    switch (perm.getActions()) {
      case "read":
        return checkPropertyReadPermission(perm, ctx, perm.getName());

      case "write":
        return checkPropertyWritePermission(perm, ctx, perm.getName());

      case "read,write":
        if (perm.getName().equals("*")) {
          return true;
        } else {
          return checkPropertyReadPermission(perm, ctx, perm.getName())
              && checkPropertyWritePermission(perm, ctx, perm.getName());
        }

      default:
        return false;
    }
  }

  protected boolean checkPropertyReadPermission(
      PropertyPermission perm, Object ctx, String property) {
    return defaultCheckPropertyReadPermission(perm, ctx, property);
  }

  protected final boolean defaultCheckPropertyReadPermission(
      PropertyPermission perm, Object ctx, String property) {
    return isBuiltinProperty(property);
  }

  /*
   * Minimal set of properties needed to keep JVM from crashing itself
   */
  protected final boolean minimalCheckPropertyReadPermission(
      PropertyPermission perm, Object ctx, String property) {
    switch (property) {
      case "sun.boot.class.path":
      case "sun.reflect.noInflation":
      case "sun.reflect.inflationThreshold":
      case "sun.nio.cs.bugLevel":
      case "java.system.class.loader":
      case "java.protocol.handler.pkgs":
      case "java.vm.specification.version":
      case "java.vm.specification.name":
      case "java.vm.specification.vendor":
      case "java.vm.version":
      case "java.vm.name":
      case "java.vm.vendor":
      case "java.vm.info":
      case "java.library.path":
      case "java.class.path":
      case "java.endorsed.dirs":
      case "java.ext.dirs":
      case "java.version":
      case "java.home":
      case "file.encoding":
      case "sun.boot.library.path":
      case "sun.jnu.encoding":
      case "jdk.module.main":
      case "jdk.debug":
      case "jdk.instrument.traceUsage":
      case "jdk.util.jar.version":
      case "jdk.util.jar.enableMultiRelease":
      case "jdk.jar.maxSignatureFileSize":
      case "jdk.util.zip.disableZip64ExtraFieldValidation":
      case "user.dir":
      case "ibm.java9.forceCommonCleanerShutdown":
      case "com.ibm.dbgmalloc":
      case "com.ibm.tools.attach.shutdown_timeout":
      case "ibm.system.encoding":
      case "os.name":
      case "JAVABIDI":
        return true;
    }

    if (property.startsWith("java.lang.invoke.MethodHandle")) {
      return true;
    }

    return false;
  }

  protected boolean checkPropertyWritePermission(
      PropertyPermission perm, Object ctx, String property) {
    return defaultCheckPropertyWritePermission(perm, ctx, property);
  }

  protected boolean defaultCheckPropertyWritePermission(
      PropertyPermission perm, Object ctx, String property) {
    switch (property) {
      case "apple.awt.application.name":
        // JDK21 triggers this write -- even for non-AWT / non-GUI apps???
        return true;

      default:
        return isUserLocaleProperty(property);
    }
  }

  protected static final boolean isBuiltinProperty(String propertyName) {
    return isSunProperty(propertyName)
        || isJavaProperty(propertyName)
        || isJdkProperty(propertyName)
        || isJmxProperty(propertyName)
        || isOsProperty(propertyName)
        || isUserLocaleProperty(propertyName)
        || isGraalProperty(propertyName)
        || isAzulProperty(propertyName)
        || isIbmProperty(propertyName)
        || isProxyProperty(propertyName)
        || isReflectProperty(propertyName)
        || isAppleProperty(propertyName)
        || isFileProperty(propertyName);
  }

  protected static final boolean isSunProperty(String propertyName) {
    return propertyName.startsWith("sun.") || propertyName.startsWith("com.sun.");
  }

  protected static final boolean isJavaProperty(String propertyName) {
    return propertyName.startsWith("java.") || propertyName.startsWith("javax.");
  }

  protected static final boolean isJdkProperty(String propertyName) {
    return propertyName.startsWith("jdk.");
  }

  protected static final boolean isJmxProperty(String propertyName) {
    return propertyName.startsWith("jmx.");
  }

  protected static final boolean isOsProperty(String propertyName) {
    return propertyName.startsWith("os.") || propertyName.equals("path.separator");
  }

  protected static final boolean isFileProperty(String propertyName) {
    return propertyName.startsWith("file.");
  }

  protected static final boolean isUserLocaleProperty(String propertyName) {
    return propertyName.startsWith("user.");
  }

  protected static final boolean isGraalProperty(String propertyName) {
    return propertyName.startsWith("org.graalvm.");
  }

  protected static final boolean isAzulProperty(String propertyName) {
    return propertyName.startsWith("com.azul.");
  }

  protected static final boolean isIbmProperty(String propertyName) {
    // IBM specific properties w/o IBM in the name
    switch (propertyName) {
      case "file.encoding":
      case "JAVABIDI":
        return true;
    }
    return propertyName.startsWith("ibm.") || propertyName.startsWith("com.ibm.");
  }

  protected static final boolean isAppleProperty(String propertyName) {
    return propertyName.startsWith("apple.");
  }

  protected static final boolean isReflectProperty(String propertyName) {
    switch (propertyName) {
      case "impl.prefix":
        return true;

      default:
        return false;
    }
  }

  protected static final boolean isProxyProperty(String propertyName) {
    switch (propertyName) {
      case "http.proxyHost":
      case "proxyHost":
      case "socksProxyHost":
      case "http.nonProxyHosts":
        return true;

      default:
        return false;
    }
  }

  protected boolean checkRuntimeMBeanProviderAccess(RuntimePermission perm, Object ctx) {
    return defaultCheckRuntimeMBeanProviderAccess(perm, ctx);
  }

  protected final boolean defaultCheckRuntimeMBeanProviderAccess(
      RuntimePermission perm, Object ctx) {
    return false;
  }

  protected boolean checkMBeanServerPermission(MBeanServerPermission perm, Object ctx) {
    return defaultCheckMBeanServerPermission(perm, ctx);
  }

  protected boolean defaultCheckMBeanServerPermission(MBeanServerPermission perm, Object ctx) {
    return false;
  }

  final boolean checkSocketPermission(SocketPermission perm, Object ctx) {
    switch (perm.getActions()) {
      case "resolve":
        return checkSocketResolve(perm, ctx, perm.getName());

      case "connect,resolve":
        {
          String name = perm.getName();
          int colonPos = name.indexOf(':');

          String host = name.substring(0, colonPos);
          int port = Integer.parseInt(name.substring(colonPos + 1));

          return checkSocketResolve(perm, ctx, host) && checkSocketConnect(perm, ctx, host, port);
        }

      default:
        return false;
    }
  }

  protected boolean checkSocketResolve(SocketPermission perm, Object ctx, String host) {
    return defaultCheckSocketResolve(perm, ctx, host);
  }

  protected final boolean defaultCheckSocketResolve(
      SocketPermission perm, Object ctx, String host) {
    return true;
  }

  protected boolean checkSocketConnect(SocketPermission perm, Object ctx, String host, int port) {
    return defaultCheckSocketConnect(perm, ctx, host, port);
  }

  protected final boolean defaultCheckSocketConnect(
      SocketPermission perm, Object ctx, String host, int port) {
    return true;
  }

  void block(Permission perm) {
    throw new SecurityException("Blocked " + perm);
  }
}
