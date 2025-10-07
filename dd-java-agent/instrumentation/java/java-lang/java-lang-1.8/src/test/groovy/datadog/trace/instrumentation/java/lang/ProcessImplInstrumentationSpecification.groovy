package datadog.trace.instrumentation.java.lang

import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.agent.test.asserts.SpanAssert
import datadog.trace.agent.test.utils.TraceUtils
import datadog.trace.bootstrap.ActiveSubsystems

import java.util.concurrent.TimeUnit

class ProcessImplInstrumentationSpecification extends InstrumentationSpecification {

  boolean previousAppsecState = false

  void setup() {
    previousAppsecState = ActiveSubsystems.APPSEC_ACTIVE
  }

  void cleanup() {
    ActiveSubsystems.APPSEC_ACTIVE = previousAppsecState
  }

  static interface ProcessStarter {
    String name()
    Process start(ArrayList<String> command)
  }

  static ProcessStarter processBuilderStarter = new ProcessStarter() {
    String name() {
      return 'ProcessBuilder'
    }

    Process start(ArrayList<String> command) {
      new ProcessBuilder(command).start()
    }
  }

  static ProcessStarter runtimeExecStarter = new ProcessStarter() {
    String name() {
      return 'RuntimeExec'
    }

    Process start(ArrayList<String> command) {
      Runtime.runtime.exec(command as String[])
    }
  }

  void assertProcessSpan(final SpanAssert it, final ArrayList<String> command) {
    assertProcessSpan(it, command, 0)
  }

  void assertProcessSpan(final SpanAssert it, final ArrayList<String> command, final int exitCode) {
    it.resourceName 'sh'
    it.spanType 'system'
    it.operationName 'command_execution'
    it.tags {
      tag 'component', 'subprocess'
      tag 'cmd.exec', '[' + command.collect({ "\"${it}\"" }).join(',') + ']'
      tag 'cmd.exit_code', Integer.toString(exitCode)
      defaultTags(false, false)
    }
  }

  void 'creates a root span with #name'() {
    when:
    def command = ['/bin/sh', '-c', 'echo 42']
    def builder = new ProcessBuilder(command)
    Process p = builder.start()
    def output = p.inputStream.text
    def terminated = p.waitFor(5, TimeUnit.SECONDS)

    then:
    output == "42\n"
    terminated
    assertTraces(1) {
      trace(1) {
        span(0) {
          assertProcessSpan(it, command)
        }
      }
    }

    where:
    name                         | starter               | _
    processBuilderStarter.name() | processBuilderStarter | _
    runtimeExecStarter.name()    | runtimeExecStarter    | _
  }

  void 'creates a child span with #name'() {
    when:
    def command = ['/bin/sh', '-c', 'echo 42']
    def terminated = TraceUtils.runUnderTrace("parent", false) {
      def builder = new ProcessBuilder(command)
      Process p = builder.start()
      p.waitFor(5, TimeUnit.SECONDS)
    }

    then:
    terminated
    assertTraces(1) {
      trace(2) {
        sortSpansByStart()
        span(0) {
          operationName 'parent'
        }
        span(1) {
          assertProcessSpan(it, command)
          childOf span(0)
        }
      }
    }

    where:
    name                         | starter               | _
    processBuilderStarter.name() | processBuilderStarter | _
    runtimeExecStarter.name()    | runtimeExecStarter    | _
  }

  void 'creates two sibling child spans with #name'() {
    when:
    def command1 = ['/bin/sh', '-c', 'sleep 0.5']
    def command2 = ['/bin/sh', '-c', 'echo 42']
    def terminated = TraceUtils.runUnderTrace("parent", false) {
      Process p1 = new ProcessBuilder(command1).start()
      Process p2 = new ProcessBuilder(command2).start()
      p2.waitFor(5, TimeUnit.SECONDS)
      p1.waitFor(5, TimeUnit.SECONDS)
    }

    then:
    terminated
    assertTraces(1) {
      trace(3) {
        sortSpansByStart()
        span(0) {
          operationName 'parent'
        }
        span(1) {
          assertProcessSpan(it, command1)
          childOf span(0)
        }
        span(2) {
          assertProcessSpan(it, command2)
          childOf span(0)
        }
      }
    }

    where:
    name                         | starter               | _
    processBuilderStarter.name() | processBuilderStarter | _
    runtimeExecStarter.name()    | runtimeExecStarter    | _
  }

  void 'span only has executable if appsec is disabled with #name'() {
    setup:
    ActiveSubsystems.APPSEC_ACTIVE = false

    when:
    def command = ['/bin/sh', '-c', 'echo 42']
    def builder = new ProcessBuilder(command)
    Process p = builder.start()
    def output = p.inputStream.text
    def terminated = p.waitFor(5, TimeUnit.SECONDS)

    then:
    output == "42\n"
    terminated
    assertTraces(1) {
      trace(1) {
        span(0) {
          assertProcessSpan(it, ['/bin/sh'])
        }
      }
    }

    where:
    name                         | starter               | _
    processBuilderStarter.name() | processBuilderStarter | _
    runtimeExecStarter.name()    | runtimeExecStarter    | _
  }

  void 'the exit code is correctly reported'() {
    when:
    def command = ['/bin/sh', '-c', 'exit 33']
    def builder = new ProcessBuilder(command)
    Process p = builder.start()
    def terminated = p.waitFor(5, TimeUnit.SECONDS)

    then:
    terminated
    assertTraces(1) {
      trace(1) {
        span(0) {
          assertProcessSpan(it, command, 33)
        }
      }
    }
  }

  void 'can handle waiting on another thread'() {
    when:
    // sleep a bit so that it doesn't all happen on the same thread
    def command = ['/bin/sh', '-c', 'sleep 0.5; echo 42']
    def builder = new ProcessBuilder(command)
    Process p = builder.start()
    String out
    Thread.start {
      out = p.inputStream.text
      p.waitFor()
    }.join(5000)

    then:
    out == '42\n'
    assertTraces(1) {
      trace(1) {
        span(0) {
          assertProcessSpan(it, command)
          it.duration { it >= 500_000_000 } // 500 ms (we sleep for 0.5 s)
        }
      }
    }
  }

  void 'command cannot be executed'() {
    when:
    def builder = new ProcessBuilder('/bin/does-not-exist')
    builder.start()

    then:
    def ex = thrown IOException

    and:
    assertTraces(1) {
      trace(1) {
        span(0) {
          resourceName 'does-not-exist'
          spanType 'system'
          operationName 'command_execution'
          errored(true)
          tags {
            tag 'component', 'subprocess'
            tag 'cmd.exec', '["/bin/does-not-exist"]'
            // The captured exception in ProcessImpl is in the cause
            errorTags(ex.cause)
            defaultTags(false, false)
          }
        }
      }
    }
  }

  void 'process is destroyed'() {
    when:
    def command = ['/bin/sh', '-c', 'sleep 3600']
    def builder = new ProcessBuilder(command)
    Process p = builder.start()
    Thread.start {
      p.destroy()
    }
    def terminated = p.waitFor(5, TimeUnit.SECONDS)
    def exitValue = p.exitValue()

    then:
    terminated
    exitValue != 0
    assertTraces(1) {
      trace(1) {
        span(0) {
          assertProcessSpan(it, command, exitValue)
        }
      }
    }
  }

  void 'command is truncated'() {
    when:
    def command = ['/bin/sh', '-c', 'echo ' + ('a' * (4096 - 14 + 1))]
    def builder = new ProcessBuilder(command)
    Process p = builder.start()
    Thread.start { p.inputStream.text }
    def terminated = p.waitFor(5, TimeUnit.SECONDS)

    then:
    terminated
    assertTraces(1) {
      trace(1) {
        span(0) {
          resourceName 'sh'
          spanType 'system'
          operationName 'command_execution'
          tags {
            tag 'component', 'subprocess'
            tag 'cmd.truncated', 'true'
            tag 'cmd.exec', '["/bin/sh","-c"]'
            tag 'cmd.exit_code', "0"
            defaultTags(false, false)
          }
        }
      }
    }
  }

  void 'redaction'() {
    when:
    def builder = new ProcessBuilder(command)
    builder.start()

    then:
    def ex = thrown IOException

    and:
    assertTraces(1) {
      trace(1) {
        span(0) {
          resourceName { true } // Ignore
          spanType 'system'
          operationName 'command_execution'
          errored(true)
          tags {
            tag 'component', 'subprocess'
            tag 'cmd.exec', expected
            errorTags(ex.cause)
            defaultTags(false, false)
          }
        }
      }
    }

    where:
    command                                                 | expected
    ['/does/not/exist/cmd', '--pass', 'abc', '--token=def'] | '["/does/not/exist/cmd","--pass","?","--token=?"]'
    ['/does/not/exist/md5', '-s', 'pony']                   | '["/does/not/exist/md5","?","?"]'
  }
}
