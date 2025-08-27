package datadog.gradle.plugin.muzzle

import org.gradle.workers.WorkAction

import java.lang.reflect.Method

abstract class MuzzleAction implements WorkAction<MuzzleWorkParameters> {
  private static final Object lock = new Object()
  private static ClassLoader bootCL
  private static ClassLoader toolCL
  private static volatile long lastBuildStamp

  @Override
  void execute() {
    // reset shared class-loaders each time a new build starts
    long buildStamp = parameters.buildStartedTime.get()
    if (lastBuildStamp < buildStamp || !bootCL || !toolCL) {
      synchronized (lock) {
        if (lastBuildStamp < buildStamp || !bootCL || !toolCL) {
          bootCL = createClassLoader(parameters.bootstrapClassPath)
          toolCL = createClassLoader(parameters.toolingClassPath, bootCL)
          lastBuildStamp = buildStamp
        }
      }
    }
    ClassLoader instCL = createClassLoader(parameters.instrumentationClassPath, toolCL)
    ClassLoader testCL = createClassLoader(parameters.testApplicationClassPath, bootCL)
    boolean assertPass = parameters.assertPass.get()
    String muzzleDirective = parameters.muzzleDirective.getOrNull()
      Method assertionMethod = instCL.loadClass('datadog.trace.agent.tooling.muzzle.MuzzleVersionScanPlugin')
      .getMethod('assertInstrumentationMuzzled', ClassLoader, ClassLoader, boolean, String)
    assertionMethod.invoke(null, instCL, testCL, assertPass, muzzleDirective)
  }

  static ClassLoader createClassLoader(cp, parent = ClassLoader.systemClassLoader) {
    return new URLClassLoader(cp*.toURI()*.toURL() as URL[], parent as ClassLoader)
  }
}
