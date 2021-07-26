package io.sqreen.testapp.sampleapp

import groovy.util.logging.Slf4j

import javax.annotation.PostConstruct

@Slf4j
class AppSecInfo {

  Class appSecSystemClass
  String problem
  String ddVersion

  AppSecInfo() {
    problem = 'not initialized'
  }

  @PostConstruct
  void init() {
    Class bootstrapAgentClass
    try {
      bootstrapAgentClass = ClassLoader.systemClassLoader.loadClass('datadog.trace.bootstrap.Agent')
      ClassLoader appsecClassloader = bootstrapAgentClass.@APPSEC_CLASSLOADER
      appSecSystemClass = appsecClassloader.loadClass('com.datadog.appsec.AppSecSystem')
    } catch (ClassNotFoundException cnf) {
      problem = 'Datadog java agent not detected'
      log.warn('Datadog java agent was not detected')
      return
    }
    if (!appSecSystemClass.started) {
      problem = "AppSec not started"
      return
    }

    ClassLoader instClassloader = bootstrapAgentClass.@AGENT_CLASSLOADER
    def ddTraceCoreInfoClass
    try {
      ddTraceCoreInfoClass = instClassloader.loadClass('datadog.trace.agent.core.DDTraceCoreInfo')
    } catch (ClassNotFoundException cnf) {
      // for builds with -PdisableShadowRelocate=true
      ddTraceCoreInfoClass = instClassloader.loadClass('datadog.trace.core.DDTraceCoreInfo')
    }
    ddVersion = ddTraceCoreInfoClass.@VERSION

    problem = null
  }

  String getApplicationProviderVersion() {
    'not supported yet'
  }

  Collection<String> getRuleNames() {
    []
  }

  String getXxeFilesLocation() {
    getClass().getClassLoader().getResource('xxe').toExternalForm()
  }

  String dumpRule(String rule) {
    'not supported yet'
  }

  String getPackId() {
    'not supported yet'
  }
}
