package stackstate.trace.agent.integration.classloading

import com.google.common.collect.MapMaker
import com.google.common.reflect.ClassPath
import datadog.trace.agent.test.IntegrationTestUtils
import io.opentracing.util.GlobalTracer
import spock.lang.Specification

import java.lang.reflect.Field

class ShadowPackageRenamingTest extends Specification {
  def "agent dependencies renamed"() {
    setup:
    final Class<?> stsClass =
      IntegrationTestUtils.getAgentClassLoader()
        .loadClass("stackstate.trace.agent.tooling.AgentInstaller")
    final String userGuava =
      MapMaker.getProtectionDomain().getCodeSource().getLocation().getFile()
    final String agentGuavaDep =
      stsClass
        .getClassLoader()
        .loadClass("com.google.common.collect.MapMaker")
        .getProtectionDomain()
        .getCodeSource()
        .getLocation()
        .getFile()
    final String agentSource =
      stsClass.getProtectionDomain().getCodeSource().getLocation().getFile()

    expect:
    agentSource.matches(".*/agent-tooling-and-instrumentation[^/]*.jar")
    agentSource == agentGuavaDep
    agentSource != userGuava
  }

  def "java getLogger rewritten to safe logger"() {
    setup:
    Field logField = GlobalTracer.getDeclaredField("LOGGER")
    logField.setAccessible(true)
    Object logger = logField.get(null)

    expect:
    !logger.getClass().getName().startsWith("java.util.logging")

    cleanup:
    logField?.setAccessible(false)
  }

  def "agent classes not visible"() {
    when:
    ClassLoader.getSystemClassLoader().loadClass("stackstate.trace.agent.tooling.AgentInstaller")
    then:
    thrown ClassNotFoundException
  }

  def "agent jar contains no bootstrap classes"() {
   setup:
   final ClassPath agentClasspath = ClassPath.from(IntegrationTestUtils.getAgentClassLoader())

   final ClassPath bootstrapClasspath = ClassPath.from(IntegrationTestUtils.getBootstrapProxy())
   final Set<String> bootstrapClasses = new HashSet<>()
   final String[] bootstrapPrefixes = IntegrationTestUtils.getBootstrapPackagePrefixes()
   final String[] agentPrefixes = IntegrationTestUtils.getAgentPackagePrefixes()
   final List<String> badBootstrapPrefixes = []
   final List<String> badAgentPrefixes = []
   for (ClassPath.ClassInfo info : bootstrapClasspath.getAllClasses()) {
     bootstrapClasses.add(info.getName())
     // make sure all bootstrap classes can be loaded from system
     ClassLoader.getSystemClassLoader().loadClass(info.getName())
     boolean goodPrefix = false
     for (int i = 0; i < bootstrapPrefixes.length; ++i) {
       if (info.getName().startsWith(bootstrapPrefixes[i])) {
         goodPrefix = true
         break
       }
     }
     if (!goodPrefix) {
       badBootstrapPrefixes.add(info.getName())
     }
   }

   final List<ClassPath.ClassInfo> duplicateClassFile = new ArrayList<>()
   for (ClassPath.ClassInfo classInfo : agentClasspath.getAllClasses()) {
     if (bootstrapClasses.contains(classInfo.getName())) {
       duplicateClassFile.add(classInfo)
     }
     boolean goodPrefix = false
     for (int i = 0; i < agentPrefixes.length; ++i) {
       if (classInfo.getName().startsWith(agentPrefixes[i])) {
         goodPrefix = true
         break
       }
     }
     if (!goodPrefix) {
       badAgentPrefixes.add(classInfo.getName())
     }
   }

   expect:
   duplicateClassFile == []
   badBootstrapPrefixes == []
   badAgentPrefixes == []
  }
}
