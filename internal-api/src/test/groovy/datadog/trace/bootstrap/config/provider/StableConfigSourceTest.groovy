package datadog.trace.bootstrap.config.provider

import datadog.trace.api.ConfigOrigin
import datadog.trace.test.util.DDSpecification
import org.yaml.snakeyaml.DumperOptions
import org.yaml.snakeyaml.Yaml

class StableConfigSourceTest extends DDSpecification {

  def "test file doesn't exist"() {
    setup:
    StableConfigSource config = new StableConfigSource(StableConfigSource.USER_STABLE_CONFIG_PATH, ConfigOrigin.USER_STABLE_CONFIG)

    expect:
    config.getKeys().size() == 0
    // How to check that the "file does not exist" error was logged?
  }

  def "test valid file"() {
    // test empty file
    when:
    def path = StableConfigSource.USER_STABLE_CONFIG_PATH
    try {
      File file = new File(path)
      file.createNewFile()
    } catch (IOException e) {
      // fail fast?
      System.out.println("Error creating file")
      e.printStackTrace()
    }
    StableConfigSource config = new StableConfigSource(path, ConfigOrigin.USER_STABLE_CONFIG)

    then:
    config.getKeys().size() == 0

    // test populated file
    //    when:
    //    def key1 = "dd_first_key"
    //    def val1 = "dd_first_val"
    //    def key2 = "dd_second_key"
    //    def val2 = "dd_second_val"
    //    // Create the map that will be used to populate the config file
    //    Map<String, Object> data = new HashMap<>();
    //    data.put("apm_configuration_default", new HashMap<String, Object>() {{
    //      put(key1, val1);
    //      put(key2, val2);
    //    }})
    //
    //    DumperOptions options = new DumperOptions();
    //    options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
    //
    //    // Prepare to write the data map to the file in yaml format
    //    Yaml yaml = new Yaml(options);
    //
    //    try (FileWriter writer = new FileWriter(path)) {
    //      yaml.dump(data, writer);
    //    } catch (IOException e) {
    //      System.err.println("Error writing to file: " + e.getMessage());
    //      // fail fast?
    //    }
    //
    //    then:
    //    StableConfigSource config2 = new StableConfigSource(StableConfigSource.USER_STABLE_CONFIG_PATH, ConfigOrigin.USER_STABLE_CONFIG);
    //    config2.getKeys().size() == 2
    //    config2.get(key1) == val1
    //    config2.get(key2) == val2
  }
}
