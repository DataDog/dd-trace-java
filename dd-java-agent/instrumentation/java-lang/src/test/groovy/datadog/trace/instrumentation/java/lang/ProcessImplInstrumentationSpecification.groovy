package datadog.trace.instrumentation.java.lang

import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.api.gateway.RequestContextSlot
import datadog.trace.bootstrap.ActiveSubsystems
import datadog.trace.core.DDSpan

import java.util.concurrent.TimeUnit

class ProcessImplInstrumentationSpecification extends AgentTestRunner {
  def ss = TEST_TRACER.getSubscriptionService(RequestContextSlot.APPSEC)

  void cleanup() {
    ss.reset()
  }

  void 'creates a span in a normal case'() {
    when:
    def builder = new ProcessBuilder('/bin/sh', '-c', 'echo 42')
    Process p = builder.start()
    String output = p.inputStream.text
    TEST_WRITER.waitForTraces(1)
    DDSpan span = TEST_WRITER[0][0]

    then:
    output == "42\n"
    span.tags['cmd.exec'] == '["/bin/sh","-c","echo 42"]'
    span.tags['cmd.exit_code'] == 0
    span.spanType == 'system'
    span.resourceName == 'sh'
    span.spanName == 'command_execution'
  }

  void 'span only has executable if appsec is disabled'() {
    setup:
    ActiveSubsystems.APPSEC_ACTIVE = false

    when:
    def builder = new ProcessBuilder('/bin/sh', '-c', 'echo 42')
    Process p = builder.start()
    Thread.start { p.inputStream.text }
    p.waitFor(5, TimeUnit.SECONDS)
    TEST_WRITER.waitForTraces(1)
    DDSpan span = TEST_WRITER[0][0]

    then:
    span.tags['cmd.exec'] == '["/bin/sh"]'
  }

  void 'variant with Runtime exec'() {
    when:
    Process p = Runtime.runtime.exec('/bin/sh -c true')
    p.waitFor(5, TimeUnit.SECONDS)
    TEST_WRITER.waitForTraces(1)
    DDSpan span = TEST_WRITER[0][0]

    then:
    span.tags['cmd.exec'] == '["/bin/sh","-c","true"]'
  }

  void 'the exit code is correctly reported'() {
    when:
    def builder = new ProcessBuilder('/bin/sh', '-c', 'exit 33')
    Process p = builder.start()
    p.waitFor(5, TimeUnit.SECONDS)
    TEST_WRITER.waitForTraces(1)
    DDSpan span = TEST_WRITER[0][0]

    then:
    span.tags['cmd.exit_code'] == 33
  }

  void 'can handle waiting on another thread'() {
    when:
    // sleep a bit so that it doesn't all happen on the same thread
    def builder = new ProcessBuilder('/bin/sh', '-c', 'sleep 0.5; echo 42')
    Process p = builder.start()
    def out
    Thread.start {
      out = p.inputStream.text
      p.waitFor()
    }.join(5000)
    TEST_WRITER.waitForTraces(1)
    DDSpan span = TEST_WRITER[0][0]

    then:
    out == '42\n'
    span.getDurationNano() >= 500_000_000 // 500 ms (we sleep for 0.5 s)
    span.tags['cmd.exit_code'] == 0
  }

  void 'command cannot be executed'() {
    when:
    def builder = new ProcessBuilder('/bin/does-not-exist')
    builder.start()

    then:
    thrown IOException

    when:
    TEST_WRITER.waitForTraces(1)
    DDSpan span = TEST_WRITER[0][0]

    then:
    span.tags['cmd.exec'] == '["/bin/does-not-exist"]'
    span.tags['error.message'] != null
    span.isError() == true
  }

  void 'process is destroyed'() {
    when:
    def builder = new ProcessBuilder('/bin/sh', '-c', 'sleep 3600')
    Process p = builder.start()
    Thread.start {
      p.destroy()
    }
    p.waitFor(5, TimeUnit.SECONDS)
    TEST_WRITER.waitForTraces(1)
    DDSpan span = TEST_WRITER[0][0]

    then:
    span.tags['cmd.exit_code'] != 0
  }

  void 'command is truncated'() {
    when:
    def builder = new ProcessBuilder('/bin/sh', '-c', 'echo ' + ('a' * (4096 - 14 + 1)))
    Process p = builder.start()
    Thread.start { p.inputStream.text }
    p.waitFor(5, TimeUnit.SECONDS)
    TEST_WRITER.waitForTraces(1)
    DDSpan span = TEST_WRITER[0][0]

    then:
    span.tags['cmd.truncated'] == 'true'
    span.tags['cmd.exec'] == '["/bin/sh","-c"]'
  }

  void redactions() {
    when:
    def builder = new ProcessBuilder(command)
    builder.start()

    then:
    thrown IOException

    when:
    TEST_WRITER.waitForTraces(1)
    DDSpan span = TEST_WRITER[0][0]

    then:
    span.tags['cmd.exec'] == expected

    where:
    command | expected
    ['cmd', '--pass', 'abc', '--token=def'] | '["cmd","--pass","?","--token=?"]'
    ['/does/not/exist/md5', '-s', 'pony'] | '["/does/not/exist/md5","?","?"]'
  }
}
