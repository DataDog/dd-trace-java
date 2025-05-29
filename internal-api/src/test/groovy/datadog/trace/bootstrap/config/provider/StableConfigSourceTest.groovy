package datadog.trace.bootstrap.config.provider

import static java.util.Collections.singletonMap

import datadog.trace.api.ConfigOrigin
import datadog.trace.bootstrap.config.provider.stableconfig.Rule
import datadog.trace.bootstrap.config.provider.stableconfig.Selector
import datadog.trace.bootstrap.config.provider.stableconfig.StableConfig
import datadog.trace.test.util.DDSpecification
import org.snakeyaml.engine.v2.api.Dump
import org.snakeyaml.engine.v2.api.DumpSettings

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

class StableConfigSourceTest extends DDSpecification {

  def "test file doesn't exist"() {
    setup:
    StableConfigSource config = new StableConfigSource(StableConfigSource.LOCAL_STABLE_CONFIG_PATH, ConfigOrigin.LOCAL_STABLE_CONFIG)

    expect:
    config.getKeys().size() == 0
    config.getConfigId() == null
  }

  def "test empty file"() {
    when:
    Path filePath = Files.createTempFile("testFile_", ".yaml")
    then:
    if (filePath == null) {
      throw new AssertionError("Failed to create: " + filePath)
    }

    when:
    StableConfigSource config = new StableConfigSource(filePath.toString(), ConfigOrigin.LOCAL_STABLE_CONFIG)
    then:
    config.getKeys().size() == 0
    config.getConfigId() == null
  }

  def "test file invalid format"() {
    // StableConfigSource must handle the exception thrown by StableConfigParser.parse(filePath) gracefully
    when:
    Path filePath = Files.createTempFile("testFile_", ".yaml")
    then:
    if (filePath == null) {
      throw new AssertionError("Failed to create: " + filePath)
    }

    when:
    writeFileRaw(filePath, configId, data)
    StableConfigSource stableCfg = new StableConfigSource(filePath.toString(), ConfigOrigin.LOCAL_STABLE_CONFIG)

    then:
    stableCfg.getConfigId() == null
    stableCfg.getKeys().size() == 0
    Files.delete(filePath)

    where:
    configId | data
    null     | corruptYaml
    "12345"  | "this is not yaml format!"
  }

  def "test file valid format"() {
    when:
    Path filePath = Files.createTempFile("testFile_", ".yaml")
    then:
    if (filePath == null) {
      throw new AssertionError("Failed to create: " + filePath)
    }

    when:
    StableConfig stableConfigYaml = new StableConfig(configId, defaultConfigs)
    writeFileYaml(filePath, stableConfigYaml)
    StableConfigSource stableCfg = new StableConfigSource(filePath.toString(), ConfigOrigin.LOCAL_STABLE_CONFIG)

    then:
    for (key in defaultConfigs.keySet()) {
      String keyString = (String) key
      keyString = keyString.substring(4) // Cut `DD_`
      stableCfg.get(keyString) == defaultConfigs.get(key)
    }
    // All configs from MatchingRule should be applied
    if (ruleConfigs.contains(sampleMatchingRule)) {
      for (key in sampleMatchingRule.getConfiguration().keySet()) {
        String keyString = (String) key
        keyString = keyString.substring(4) // Cut `DD_`
        stableCfg.get(keyString) == defaultConfigs.get(key)
      }
    }
    // None of the configs from NonMatchingRule should be applied
    if (ruleConfigs.contains(sampleNonMatchingRule)) {
      Set<String> cfgKeys = stableCfg.getKeys()
      for (key in sampleMatchingRule.getConfiguration().keySet()) {
        String keyString = (String) key
        keyString = keyString.substring(4) // Cut `DD_`
        !cfgKeys.contains(keyString)
      }
    }
    Files.delete(filePath)

    where:
    configId | defaultConfigs                             | ruleConfigs
    ""       | [:]                                        | Arrays.asList(new Rule())
    "12345"  | ["DD_KEY_ONE": "one", "DD_KEY_TWO": "two"] | Arrays.asList(sampleMatchingRule, sampleNonMatchingRule)
  }

  // Corrupt YAML string variable used for testing, defined outside the 'where' block for readability
  def static corruptYaml = ''' 
        abc: 123
        def:
          ghi: "jkl"
          lmn: 456
    '''

  // Matching and non-matching Rules used for testing, defined outside the 'where' block for readability
  def static sampleMatchingRule = new Rule(Arrays.asList(new Selector("origin", "language", Arrays.asList("Java"), null)), singletonMap("DD_KEY_THREE", "three"))
  def static sampleNonMatchingRule = new Rule(Arrays.asList(new Selector("origin", "language", Arrays.asList("Golang"), null)), singletonMap("DD_KEY_FOUR", "four"))

  def writeFileYaml(Path filePath, StableConfig stableConfigs) {
    Map<String, Object> yamlData = new HashMap<>()

    if (stableConfigs.getConfigId() != null && !stableConfigs.getConfigId().isEmpty()) {
      yamlData.put("config_id", stableConfigs.getConfigId())
    }

    if (stableConfigs.getApmConfigurationDefault() != null && !stableConfigs.getApmConfigurationDefault().isEmpty()) {
      yamlData.put("apm_configuration_default", stableConfigs.getApmConfigurationDefault())
    }

    DumpSettings settings = DumpSettings.builder().build()
    Dump dump = new Dump(settings)
    String yamlContent = dump.dumpToString(yamlData)

    try (FileWriter writer = new FileWriter(filePath.toFile())) {
      writer.write(yamlContent)
    }
  }

  // Use this if you want to explicitly write/test configId
  def writeFileRaw(Path filePath, String configId, String data) {
    data = configId + "\n" + data
    StandardOpenOption[] openOpts = [StandardOpenOption.WRITE] as StandardOpenOption[]
    Files.write(filePath, data.getBytes(), openOpts)
  }
}
