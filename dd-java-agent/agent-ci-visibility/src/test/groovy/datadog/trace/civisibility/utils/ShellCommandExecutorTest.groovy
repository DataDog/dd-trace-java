package datadog.trace.civisibility.utils

import spock.lang.Specification
import spock.lang.TempDir

import java.util.concurrent.TimeoutException

class ShellCommandExecutorTest extends Specification {

  private static final int SHELL_COMMAND_TIMEOUT = 5_000

  @TempDir
  File temporaryFolder

  def "test command execution"() {
    given:
    def shellCommandExecutor = new ShellCommandExecutor(temporaryFolder, SHELL_COMMAND_TIMEOUT)

    when:
    def output = shellCommandExecutor.executeCommand(IOUtils::readFully, "echo", "this is a test")

    then:
    output.trim() == "this is a test"
  }

  def "test command execution with input"() {
    given:
    def shellCommandExecutor = new ShellCommandExecutor(temporaryFolder, SHELL_COMMAND_TIMEOUT)

    when:
    def output = shellCommandExecutor.executeCommand(IOUtils::readFully, "this is a test".bytes, "cat")

    then:
    output.trim() == "this is a test"
  }

  def "test command execution timeout"() {
    given:
    def shellCommandExecutor = new ShellCommandExecutor(temporaryFolder, 1_000)

    when:
    shellCommandExecutor.executeCommand(IOUtils::readFully, "sleep", "2")

    then:
    thrown TimeoutException
  }
}
