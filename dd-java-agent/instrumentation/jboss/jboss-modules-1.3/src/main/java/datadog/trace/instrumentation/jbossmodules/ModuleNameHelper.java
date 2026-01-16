package datadog.trace.instrumentation.jbossmodules;

import datadog.trace.util.MethodHandles;
import java.lang.invoke.MethodHandle;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.annotation.Nonnull;
import org.jboss.modules.Module;
import org.jboss.modules.ModuleClassLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ModuleNameHelper {
  private ModuleNameHelper() {}

  private static final Logger LOGGER = LoggerFactory.getLogger(ModuleNameHelper.class);
  private static final MethodHandle MODULE_NAME_GETTER = resolveModuleNameGetter();

  private static MethodHandle resolveModuleNameGetter() {
    final ClassLoader classLoader = ModuleClassLoader.class.getClassLoader();
    final MethodHandles methodHandles = new MethodHandles(classLoader);
    MethodHandle ret = null;
    MethodHandle getModuleIdentifierHandle = null;
    try {
      // before 2.2
      getModuleIdentifierHandle = methodHandles.method(Module.class, "getIdentifier");
      if (getModuleIdentifierHandle != null) {
        // chains the two method handle calls
        ret =
            java.lang.invoke.MethodHandles.filterReturnValue(
                getModuleIdentifierHandle,
                methodHandles.method(
                    Class.forName("org.jboss.modules.ModuleIdentifier", false, classLoader),
                    "getName"));
      }
    } catch (Throwable ignored) {
      // here for caution. It's already caught and logged on MethodHandles
    }
    if (ret == null) {
      // 2.2 onward
      try {
        ret = methodHandles.method(Module.class, "getName");
      } catch (Throwable ignored) {
      }
    }
    if (ret == null) {
      LOGGER.debug(
          "Unable to resolve a method to establish jboss module name. If enabled, jee-split-by-deployment will not work properly");
    }
    return ret;
  }

  private static final Pattern SUBDEPLOYMENT_MATCH =
      Pattern.compile("deployment(?>.+\\.ear)?\\.(.+)\\.[j|w]ar");

  static String extractModuleName(Module module) {
    if (MODULE_NAME_GETTER == null) {
      return null;
    }
    String moduleName = null;
    try {
      moduleName = (String) MODULE_NAME_GETTER.invoke(module);
    } catch (Throwable ignored) {
    }
    return moduleName;
  }

  public static String extractDeploymentName(@Nonnull final ModuleClassLoader classLoader) {

    final String moduleName = extractModuleName(classLoader.getModule());
    if (moduleName == null) {
      return null;
    }
    final Matcher matcher = SUBDEPLOYMENT_MATCH.matcher(moduleName);
    if (matcher.matches()) {
      return matcher.group(1);
    }
    return null;
  }
}
