package datadog.crashtracking.parsers;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import org.tabletest.junit.TableTest;

public class RuntimeArgsTest {
  private static final String HOTSPOT_JVM_ARGS_PREFIX = "jvm_args:";
  private static final String J9_USER_ARG_PREFIX = "2CIUSERARG";

  @TableTest({
    "scenario                            | resource                           | isIncluded | vmArg                                                                                        ",
    "telemetry fixture logging property  | sample-crash-for-telemetry.txt     | true       | -Djava.util.logging.config.file=/opt/REDACT_THIS/REDACT_THIS/etc/java.util.logging.properties",
    "telemetry fixture dd property       | sample-crash-for-telemetry.txt     | true       | -Ddd.profiling.enabled=true                                                                  ",
    "telemetry fixture excluded property | sample-crash-for-telemetry.txt     | false      | -Dkaraf.startRemoteShell=REDACT_THIS                                                         ",
    "telemetry fixture ws provider       | sample-crash-for-telemetry.txt     | false      | -Djavax.xml.ws.spi.Provider=com.sun.xml.ws.spi.ProviderImpl                                  ",
    "telemetry with OnError              | sample-crash-for-telemetry-2.txt   | true       | -XX:OnError=/tmp/dd_crash_uploader.sh %p                                                     ",
    "telemetry jdk8                      | sample-crash-for-telemetry-3.txt   | true       | -Ddd.trace.enabled=false                                                                     ",
    "linux aarch64                       | sample-crash-linux-aarch64.txt     | true       | --add-modules=ALL-DEFAULT                                                                    ",
    "macos aarch64                       | sample-crash-macos-aarch64.txt     | true       | --enable-native-access=ALL-UNNAMED                                                           ",
    "jdk8 zip                            | sample-crash-jdk8-zip-getentry.txt | true       | -Dsun.zip.disableMemoryMapping=false                                                         "
  })
  public void testParseVmArgsHotspotArgs(String resource, boolean isIncluded, String vmArg)
      throws Exception {
    List<String> runtimeArgs = RuntimeArgs.parseVmArgs(extractHotspotJvmArgs(resource));

    assertThat(runtimeArgs).isNotNull().isNotEmpty();
    if (isIncluded) {
      assertThat(runtimeArgs).as("Expected included arg in %s", resource).contains(vmArg);
    } else {
      assertThat(runtimeArgs)
          .as("Expected excluded arg to be absent in %s", resource)
          .doesNotContain(vmArg);
    }
  }

  @TableTest({
    "scenario               | raw                                                   | expectedIncluded                                 ",
    "quoted onerror unix    | -XX:OnError=\"gcore %p;gdb -p %p\"                    | -XX:OnError=gcore %p;gdb -p %p                   ",
    "quoted onerror windows | -XX:OnError=\"userdump.exe %p\"                       | -XX:OnError=userdump.exe %p                      ",
    "quoted module path     | --module-path \"/opt/app-modules:/opt/other-modules\" | --module-path /opt/app-modules:/opt/other-modules"
  })
  public void testParseVmArgsHandlesQuotedArguments(String raw, String expectedIncluded) {
    List<String> runtimeArgs = RuntimeArgs.parseVmArgs(raw);

    assertThat(runtimeArgs).isNotNull().contains(expectedIncluded);
  }

  @TableTest({
    "scenario                    | raw                               | isIncluded | vmArg                            ",
    "java password excluded      | -Djava.net.password=hunter2       | false      | -Djava.net.password=hunter2      ",
    "sun token excluded          | -Dsun.auth.token=abc123           | false      | -Dsun.auth.token=abc123          ",
    "dd api key excluded         | -Ddd.api-key=deadbeef             | false      | -Ddd.api-key=deadbeef            ",
    "dd app key excluded         | -Ddd.app-key=deadbeef             | false      | -Ddd.app-key=deadbeef            ",
    "dd application key excluded | -Ddd.application-key=deadbeef    | false      | -Ddd.application-key=deadbeef   ",
    "java logging kept           | -Djava.util.logging.config.file=x | true       | -Djava.util.logging.config.file=x",
    "osgi install kept           | -Dosgi.install.area=/opt/app      | true       | -Dosgi.install.area=/opt/app     "
  })
  public void testParseVmArgsExcludesSecretLikeSystemProperties(
      String raw, boolean isIncluded, String vmArg) {
    List<String> runtimeArgs = RuntimeArgs.parseVmArgs(raw);

    assertThat(runtimeArgs).isNotNull();
    if (isIncluded) {
      assertThat(runtimeArgs).contains(vmArg);
    } else {
      assertThat(runtimeArgs).doesNotContain(vmArg);
    }
  }

  @TableTest({
    "scenario                    | resource                                | isIncluded | vmArg                                                                                                                                      ",
    "truncated ibmj9 optionsfile | redacted-truncated-ibmj9-8-javacore.txt | true       | -Xoptionsfile=/opt/REDACTED/java/8.0/jre/lib/ppc64/compressedrefs/options.default                                                          ",
    "truncated ibmj9 dd arg      | redacted-truncated-ibmj9-8-javacore.txt | true       | -Ddd.service=REDACTED                                                                                                                      ",
    "truncated ibmj9 osgi arg    | redacted-truncated-ibmj9-8-javacore.txt | true       | -Dosgi.install.area=/opt/REDACTED                                                                                                          ",
    "truncated ibmj9 status arg  | redacted-truncated-ibmj9-8-javacore.txt | false      | -Dwas.status.socket=REDACTED                                                                                                               ",
    "truncated ibmj9 xtq arg     | redacted-truncated-ibmj9-8-javacore.txt | false      | -Dcom.ibm.xtq.processor.overrideSecureProcessing=true                                                                                      ",
    "truncated ibmj9 command arg | redacted-truncated-ibmj9-8-javacore.txt | false      | -Dsun.java.command=com.ibm.wsspi.bootstrap.WSPreLauncher -nosplash -application com.ibm.ws.bootstrap.WSLauncher com.ibm.ws.runtime.WsServer"
  })
  public void testBuildFromJ9UserArgs(String resource, boolean isIncluded, String vmArg)
      throws Exception {
    RuntimeArgs runtimeArgsBuilder = new RuntimeArgs();
    for (String arg : extractJ9UserArgs(resource)) {
      runtimeArgsBuilder.addArg(arg);
    }
    List<String> runtimeArgs = runtimeArgsBuilder.build();

    assertThat(runtimeArgs).isNotNull();
    if (isIncluded) {
      assertThat(runtimeArgs)
          .as("Expected included arg fragment in %s", resource)
          .anyMatch(arg -> arg.contains(vmArg));
    } else {
      assertThat(runtimeArgs)
          .as("Expected excluded arg to be absent in %s", resource)
          .doesNotContain(vmArg);
    }
  }

  private String extractHotspotJvmArgs(String resource) throws IOException {
    for (String line : readFileAsString(resource).split("\n")) {
      if (line.startsWith(HOTSPOT_JVM_ARGS_PREFIX)) {
        return line.substring(HOTSPOT_JVM_ARGS_PREFIX.length()).trim();
      }
    }
    throw new IllegalArgumentException("Missing jvm_args line in " + resource);
  }

  private List<String> extractJ9UserArgs(String resource) throws IOException {
    return java.util.Arrays.stream(readFileAsString(resource).split("\n"))
        .filter(line -> line.startsWith(J9_USER_ARG_PREFIX))
        .map(line -> line.substring(J9_USER_ARG_PREFIX.length()).trim())
        .collect(Collectors.toList());
  }

  private String readFileAsString(String resource) throws IOException {
    try (InputStream stream = getClass().getClassLoader().getResourceAsStream(resource)) {
      return new BufferedReader(
              new InputStreamReader(Objects.requireNonNull(stream), StandardCharsets.UTF_8))
          .lines()
          .collect(Collectors.joining("\n"));
    }
  }
}
