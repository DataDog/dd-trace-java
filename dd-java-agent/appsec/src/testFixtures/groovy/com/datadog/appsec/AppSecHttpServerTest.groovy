package com.datadog.appsec

import datadog.communication.ddagent.SharedCommunicationObjects
import datadog.communication.monitor.Monitoring
import datadog.trace.agent.test.base.WithHttpServer
import datadog.trace.api.Config
import datadog.trace.api.appsec.AppSecEventTracker
import datadog.trace.api.gateway.RequestContextSlot
import datadog.trace.api.gateway.SubscriptionService
import datadog.trace.bootstrap.instrumentation.api.AgentTracer

abstract class AppSecHttpServerTest<SERVER> extends WithHttpServer<SERVER> {

  @Override
  protected void configurePreAgent() {
    super.configurePreAgent()
    injectSysConfig('dd.appsec.enabled', 'true')
    injectSysConfig('dd.remote_config.enabled', 'false')
  }

  def setupSpec() {
    SubscriptionService ss = AgentTracer.get().getSubscriptionService(RequestContextSlot.APPSEC)
    def sco = new SharedCommunicationObjects()
    def config = Config.get()
    sco.createRemaining(config)
    assert sco.configurationPoller(config) == null
    assert sco.monitoring instanceof Monitoring.DisabledMonitoring
    AppSecEventTracker.install()

    AppSecSystem.start(ss, sco)
  }

  void cleanupSec() {
    AppSecSystem.stop()
  }
}
