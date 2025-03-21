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
      "-javaagent",
      "-Xmx256",
      "-Xdebug",
    ]
    Map<String,List<String>> args = CLIHelper.parseJvmArgs(input)

    then:
    // -Xdebug
    args.containsKey("-Xdebug")
    args.get("-Xdebug") == [null]

    // -Xmx256
    args.containsKey("-Xmx256")
    args.get("-Xmx256") == [null]

    // -javaagent
    args.containsKey("-javaagent")
    args.get("-javaagent") == ["/path/to/dd-java-agent.jar", null]

    // -DdisableFeature
    args.containsKey("-DdisableFeature")
    args.get("-DdisableFeature") == [null]

    // -Dkey
    args.containsKey("-Dkey")
    // The data structure does not discriminate against what types of jvm args are allowed to have duplicate keys; even though typically `javaagent` is the only jvm arg that supports multiple entries
    // Therefore, system properties `-Dkey=value -Dkey=value2` will be respected by CLIHelper
    args.get("-Dkey") == ["value1", "value2"]

    //-XX:+UseG1GC
    args.containsKey("-XX:+UseG1GC")
    args.get("-XX:+UseG1GC") == [null]

    //-XX:MaxMetaspaceSize=128m
    args.containsKey("-XX:MaxMetaspaceSize")
    args.get("-XX:MaxMetaspaceSize") == ["128m"]

  }
}
