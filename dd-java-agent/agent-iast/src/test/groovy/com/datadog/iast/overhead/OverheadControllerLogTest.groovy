package com.datadog.iast.overhead

import ch.qos.logback.classic.Logger
import com.datadog.iast.IastSystem
import datadog.trace.api.Config
import datadog.trace.bootstrap.instrumentation.api.AgentSpan
import datadog.trace.test.util.DDSpecification

class OverheadControllerLogTest extends DDSpecification {

  private boolean defaultDebug
  private Logger logger

  def setup() {
    defaultDebug = IastSystem.DEBUG
    logger = OverheadController.OverheadControllerDebugAdapter.LOGGER as Logger
  }

  def cleanup() {
    IastSystem.DEBUG = defaultDebug
    OverheadController.OverheadControllerDebugAdapter.LOGGER = logger
  }

  void 'on acquire request'() {
    given:
    IastSystem.DEBUG = true
    final logger = Mock(org.slf4j.Logger)
    OverheadController.OverheadControllerDebugAdapter.LOGGER = logger
    final controller = OverheadController.build(Config.get(), null)

    when:
    controller.acquireRequest()

    then:
    noExceptionThrown()
    1 * logger.isDebugEnabled() >> true
    1 * logger.debug(_, _)

    when:
    controller.acquireRequest()

    then:
    noExceptionThrown()
    1 * logger.isDebugEnabled() >> false
    0 * _
  }

  void 'on reset request'() {
    given:
    IastSystem.DEBUG = true
    final logger = Mock(org.slf4j.Logger)
    OverheadController.OverheadControllerDebugAdapter.LOGGER = logger
    final controller = OverheadController.build(Config.get(), null)

    when:
    controller.releaseRequest()

    then:
    noExceptionThrown()
    1 * logger.isDebugEnabled() >> true
    1 * logger.debug(_, _, _)

    when:
    controller.releaseRequest()

    then:
    noExceptionThrown()
    1 * logger.isDebugEnabled() >> false
    0 * _
  }

  void 'on has quota'() {
    given:
    IastSystem.DEBUG = true
    final controller = OverheadController.build(Config.get(), null)

    when:
    controller.hasQuota(Operations.REPORT_VULNERABILITY, Mock(AgentSpan))

    then:
    noExceptionThrown()

    when:
    controller.hasQuota(Operations.REPORT_VULNERABILITY, null)

    then:
    noExceptionThrown()
  }

  void 'on consume quota'() {
    given:
    IastSystem.DEBUG = true
    final controller = OverheadController.build(Config.get(), null)

    when:
    controller.consumeQuota(Operations.REPORT_VULNERABILITY, Mock(AgentSpan))

    then:
    noExceptionThrown()

    when:
    controller.consumeQuota(Operations.REPORT_VULNERABILITY, null)

    then:
    noExceptionThrown()
  }

  void 'on reset'() {
    given:
    IastSystem.DEBUG = true
    final controller = OverheadController.build(Config.get(), null)

    when:
    controller.reset()

    then:
    noExceptionThrown()
  }
}
