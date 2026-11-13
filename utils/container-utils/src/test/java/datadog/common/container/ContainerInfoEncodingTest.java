package datadog.common.container;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ContainerInfoEncodingTest {
  @ParameterizedTest
  @ValueSource(strings = {"0::", "1:memory:"})
  void preservesCgroupPathUsingTheJvmDefault(String prefix, @TempDir Path directory)
      throws Exception {
    Path cgroup = directory.resolve("cgroup");
    Files.write(cgroup, (prefix + "/café\n").getBytes(Charset.defaultCharset()));

    ContainerInfo info = ContainerInfo.fromProcFile(cgroup);

    assertEquals(1, info.getCGroups().size());
    assertEquals("/café", info.getCGroups().get(0).getPath());
  }
}
