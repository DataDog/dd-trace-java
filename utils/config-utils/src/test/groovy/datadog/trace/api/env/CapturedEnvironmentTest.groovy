package datadog.trace.api.env

import static java.io.File.separator

import datadog.trace.api.config.GeneralConfig
import datadog.trace.test.util.DDSpecification

class CapturedEnvironmentTest extends DDSpecification {
  def "non autodetected service.name with null command"() {
    when:
    def serviceName = forkAndRunProperties('null')

    then:
    serviceName == null
  }

  def "non autodetected service.name with empty command"() {
    when:
    def serviceName = forkAndRunProperties('')

    then:
    serviceName == null
  }

  def "non autodetected service.name with all blanks command"() {
    when:
    def serviceName = forkAndRunProperties(' ')

    then:
    serviceName == null
  }

  def "set service.name by sysprop 'sun.java.command' with class"() {
    when:
    def serviceName = forkAndRunProperties('org.example.App -Dfoo=bar arg2 arg3')

    then:
    serviceName == 'org.example.App'
  }

  def "set service.name by sysprop 'sun.java.command' with jar"() {
    when:
    def serviceName = forkAndRunProperties('foo/bar/example.jar -Dfoo=bar arg2 arg3')

    then:
    serviceName == 'example'
  }

  def "set service.name with real 'sun.java.command' property"() {
    when:
    def serviceName = forkAndRunProperties(null)

    then:
    serviceName == ServiceNamePrinter.name
  }

  def "use Azure site name in Azure"() {
    when:
    def serviceName = forkAndRunProperties('foo/bar/example.jar -Dfoo=bar arg2 arg3', [
      'DD_AZURE_APP_SERVICES': '1',
      'WEBSITE_SITE_NAME': 'siteService'
    ])

    then:
    serviceName == 'siteService'
  }

  def "dont use site name when not in azure"() {
    when:
    def serviceName = forkAndRunProperties('foo/bar/example.jar -Dfoo=bar arg2 arg3', [
      'WEBSITE_SITE_NAME': 'siteService'
    ])

    then:
    serviceName == 'example'
  }

  def "dont use Azure site name when null"() {
    when:
    def serviceName = forkAndRunProperties('foo/bar/example.jar -Dfoo=bar arg2 arg3', [
      'DD_AZURE_APP_SERVICES': 'true',
    ])

    then:
    serviceName == 'example'
  }

  private static String forkAndRunProperties(String arg, Map<String, String> envVars = [:])
  throws IOException, InterruptedException {
    // Build the command to run a new Java process
    List<String> command = []
    command += System.getProperty("java.home") + separator + "bin" + separator + "java"
    command += '-cp'
    command += System.getProperty("java.class.path")
    command += ServiceNamePrinter.name
    if (arg != null) {
      command += arg
    }
    // Start the process
    ProcessBuilder processBuilder = new ProcessBuilder(command)
    processBuilder.environment().putAll(envVars)
    Process process = processBuilder.start()
    // Read and parse output and error streams
    String serviceName = ''
    try (BufferedReader reader =
    new BufferedReader(new InputStreamReader(process.getInputStream()))) {
      String line
      while ((line = reader.readLine()) != null) {
        if (serviceName != '') {
          serviceName += '\n'
        }
        serviceName += line
      }
    }
    if (serviceName == 'null') {
      serviceName = null
    }
    String error = ''
    try (BufferedReader reader =
    new BufferedReader(new InputStreamReader(process.getErrorStream()))) {
      String line
      while ((line = reader.readLine()) != null) {
        error += line + '\n'
      }
    }
    // Wait for the process to complete
    int exitCode = process.waitFor()
    // Dumping state on error
    if (exitCode != 0) {
      println("Error printing service name. Exit code $exitCode with service name: '$serviceName' and error:\n$error")
      throw new IllegalStateException('Process should exit without error')
    }
    return serviceName
  }

  static class ServiceNamePrinter {
    static void main(String[] args) {
      if (args.length > 0) {
        def sunJavaCommand = args[0]
        if (sunJavaCommand == 'null') {
          System.clearProperty('sun.java.command')
        } else {
          System.setProperty('sun.java.command', sunJavaCommand)
        }
      }
      def capturedEnv = CapturedEnvironment.get()
      def props = capturedEnv.properties
      println props.get(GeneralConfig.SERVICE_NAME)
    }
  }
}
