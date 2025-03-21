package datadog.cli
import spock.lang.Specification

class CLIHelperTest extends Specification {
  def "test parseJvmArgs"() {
    when:
    List<String> input = [
      "-XX:MaxMetaspaceSize=128m",
      "-XX:+UseG1GC",
      "-Dkey=value1",
      "-Dkey=value2",
      "-DdisableFeature",
      "-javaagent:/path/to/dd-java-agent.jar",
      "-javaagent:/path/to/another-agent.jar",
      "-javaagent",
      "-Xmx512",
      "-Xdebug",
    ]
    Map<String,List<String>> args = CLIHelper.parseJvmArgs(input)

    then:
    // -Xdebug
    args.containsKey("-Xdebug")
    args.get("-Xdebug") == [null]

    // -Xmx512
    args.containsKey("-Xmx512")
    args.get("-Xmx512") == [null]

    // -javaagent
    args.containsKey("-javaagent")
    args.get("-javaagent") == ["/path/to/dd-java-agent.jar", "/path/to/another-agent.jar", null]

    // -DdisableFeature
    args.containsKey("-DdisableFeature")
    args.get("-DdisableFeature") == [null]

    // -Dkey
    // CLIHelper does not discriminate against what types of jvm args can have duplicate values, it accepts all args found on the process
    args.containsKey("-Dkey")
    args.get("-Dkey") == ["value1", "value2"]

    //-XX:+UseG1GC
    args.containsKey("-XX:+UseG1GC")
    args.get("-XX:+UseG1GC") == [null]

    //-XX:MaxMetaspaceSize
    args.containsKey("-XX:MaxMetaspaceSize")
    args.get("-XX:MaxMetaspaceSize") == ["128m"]
  }
}
