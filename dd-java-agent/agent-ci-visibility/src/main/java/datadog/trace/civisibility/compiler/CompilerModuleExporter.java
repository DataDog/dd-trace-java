package datadog.trace.civisibility.compiler;

import datadog.trace.util.JDK9ModuleAccess;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Exports jdk.compiler internal packages to the classloader that loads dd-javac-plugin.
 *
 * <p>On JDK 16+ (strong encapsulation), dd-javac-plugin's CompilerModuleOpener uses burningwave to
 * export these packages. On JDK 26+, burningwave fails due to Unsafe restrictions (JEP 471/498).
 * This transformer intercepts dd-javac-plugin class loading and does the export using
 * Instrumentation.redefineModule() instead.
 *
 * <p>Each Maven compilation step (compile, testCompile) may use a different classloader, so we
 * track which classloaders have already been exported to and re-export for new ones.
 */
public class CompilerModuleExporter implements ClassFileTransformer {

  private static final Logger LOGGER = LoggerFactory.getLogger(CompilerModuleExporter.class);

  private static final String COMPILER_PLUGIN_CLASS_PREFIX = "datadog/compiler/";
  private static final String[] COMPILER_PACKAGES = {
    "com.sun.tools.javac.api",
    "com.sun.tools.javac.code",
    "com.sun.tools.javac.comp",
    "com.sun.tools.javac.tree",
    "com.sun.tools.javac.util"
  };

  private final Instrumentation inst;
  private final Set<ClassLoader> exportedClassLoaders =
      Collections.newSetFromMap(new ConcurrentHashMap<>());

  public CompilerModuleExporter(Instrumentation inst) {
    this.inst = inst;
  }

  @Override
  public byte[] transform(
      ClassLoader loader,
      String className,
      Class<?> classBeingRedefined,
      ProtectionDomain protectionDomain,
      byte[] classfileBuffer) {
    if (loader != null
        && className != null
        && className.startsWith(COMPILER_PLUGIN_CLASS_PREFIX)
        && exportedClassLoaders.add(loader)) {
      try {
        JDK9ModuleAccess.addModuleExports(inst, "jdk.compiler", COMPILER_PACKAGES, loader);
        LOGGER.debug("Exported jdk.compiler packages to classloader {}", loader);
      } catch (Throwable e) {
        LOGGER.debug("Could not export jdk.compiler packages for compiler plugin", e);
      }
    }
    return null; // no bytecode modification
  }
}
