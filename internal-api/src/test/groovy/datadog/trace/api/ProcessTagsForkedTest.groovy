package datadog.trace.api

import static datadog.trace.api.config.GeneralConfig.EXPERIMENTAL_PROPAGATE_PROCESS_TAGS_ENABLED

import datadog.trace.api.env.CapturedEnvironment
import datadog.trace.test.util.DDSpecification
import datadog.trace.util.TraceUtils
import java.nio.file.Paths

class ProcessTagsForkedTest extends DDSpecification {

  def originalProcessInfo

  def setup() {
    originalProcessInfo = CapturedEnvironment.get().getProcessInfo()
    ProcessTags.reset()
  }

  def cleanup() {
    CapturedEnvironment.useFixedProcessInfo(originalProcessInfo)
  }

  def 'should load default tags for jar #jar and main class #cls'() {
    given:
    CapturedEnvironment.useFixedProcessInfo(new CapturedEnvironment.ProcessInfo(cls, jar))
    ProcessTags.reset()
    def tags = ProcessTags.getTagsForSerialization()
    expect:
    tags =~ expected
    where:
    jar                                     | cls             | expected
    Paths.get("my test", "my.jar").toFile() | null            | "entrypoint.basedir:my_test,entrypoint.name:my,entrypoint.type:jar,entrypoint.workdir:[^,]+"
    Paths.get("my.jar").toFile()            | null            | "entrypoint.name:my,entrypoint.type:jar,entrypoint.workdir:[^,]+"
    null                                    | "com.test.Main" | "entrypoint.name:com.test.main,entrypoint.type:class,entrypoint.workdir:[^,]+"
    null                                    | null            | "entrypoint.workdir:[^,]+"
  }

  def 'should add process tags for service name set by user #userServiceName'() {
    given:
    if (userServiceName != null) {
      injectSysConfig("service", userServiceName)
    }
    ProcessTags.reset()
    def tags = ProcessTags.getTagsForSerialization()
    expect:
    tags =~ expected
    where:
    userServiceName | expected
    null            | "svc.auto:${TraceUtils.normalizeServiceName(Config.get().getServiceName())}"
    "custom"        | "svc.user:true"
  }

  def 'should load default tags jboss (mode #mode)'() {
    setup:
    if (jbossHome != null) {
      System.setProperty("jboss.home.dir", jbossHome)
    }
    System.setProperty(mode, "") // i.e. -D[Standalone]
    System.setProperty("jboss.server.name", serverName)
    when:
    CapturedEnvironment.useFixedProcessInfo(new CapturedEnvironment.ProcessInfo(null, new File("/somewhere/jboss-modules.jar")))
    ProcessTags.reset()
    def tags = ProcessTags.getTagsForSerialization()
    then:
    assert tags =~ expected
    cleanup:
    System.clearProperty(mode)
    System.clearProperty("jboss.home.dir")
    System.clearProperty("jboss.server.name")
    where:
    jbossHome             | mode              | serverName   | expected
    "/opt/jboss/myserver" | "[Standalone]"    | "standalone" | "entrypoint.basedir:somewhere,entrypoint.name:jboss-modules,entrypoint.type:jar,entrypoint.workdir:.+,jboss.home:myserver,jboss.mode:standalone,server.name:standalone,server.type:jboss"
    "/opt/jboss/myserver" | "[server1:12345]" | "server1"    | "entrypoint.basedir:somewhere,entrypoint.name:jboss-modules,entrypoint.type:jar,entrypoint.workdir:.+,jboss.home:myserver,jboss.mode:domain,server.name:server1,server.type:jboss"
    null                  | "[Standalone]"    | "standalone" | "entrypoint.basedir:somewhere,entrypoint.name:jboss-modules,entrypoint.type:jar,entrypoint.workdir:[^,]+" // don't expect jboss tags since home is missing
  }

  def 'should load websphere tags (#expected)'() {
    setup:
    ProcessTags.envGetter = key -> {
      switch (key) {
        case "WAS_CELL":
        return cellName
        case "SERVER_NAME":
        return serverName
        default:
        return null
      }
    }
    ProcessTags.reset()
    when:
    def tags = ProcessTags.getTagsForSerialization()
    then:
    assert tags =~ expected
    cleanup:
    ProcessTags.envGetter = System::getenv
    ProcessTags.reset()
    where:
    cellName | serverName | expected
    "cell1"  | "server1"  | "cluster.name:cell1,.+,server.name:server1,server.type:websphere.*"
    null     | "server1"  | "^((?!cluster.name|server.name|server.type).)*\$"
  }

  def 'can disable process tags'() {
    when:
    injectSysConfig(EXPERIMENTAL_PROPAGATE_PROCESS_TAGS_ENABLED, "false")
    ProcessTags.reset()
    def processTags = ProcessTags.tagsForSerialization
    then:
    assert !ProcessTags.enabled
    assert (processTags == null)
    when:
    ProcessTags.addTag("test", "value")
    then:
    assert (ProcessTags.tagsForSerialization == null)
    assert (ProcessTags.tagsAsStringList == null)
    assert (ProcessTags.tagsAsUTF8ByteStringList == null)
  }

  def 'should lazily recalculate when a tag is added'() {
    setup:
    ProcessTags.reset()
    when:
    def processTags = ProcessTags.tagsForSerialization
    def tagsAsList = ProcessTags.tagsAsStringList
    def tagsAsUtf8List = ProcessTags.tagsAsUTF8ByteStringList
    then:
    assert ProcessTags.enabled
    assert processTags != null
    assert tagsAsList != null
    assert tagsAsList.size() > 0
    assert tagsAsUtf8List != null
    assert tagsAsUtf8List.size() == tagsAsList.size()
    when:
    // add it as first pos since 0 < any other a-z
    ProcessTags.addTag("0test", "value")
    then:
    assert ProcessTags.tagsForSerialization.toString() == "0test:value,$processTags"
    def size = ProcessTags.tagsAsStringList.size()
    assert size == tagsAsList.size() + 1
    assert size == ProcessTags.tagsAsUTF8ByteStringList.size()
    assert ProcessTags.tagsAsStringList[0] == "0test:value"
    assert ProcessTags.tagsAsUTF8ByteStringList[0].toString() == "0test:value"
  }

  def 'should resolve process tag from system property via {prop} source'() {
    setup:
    System.setProperty("my.property", "my.value")
    injectSysConfig("process.tags.mapping", "{prop}my.property:some_key")
    ProcessTags.reset()
    when:
    def tags = ProcessTags.getTagsAsStringList()
    then:
    tags.contains("some_key:my.value")
    cleanup:
    System.clearProperty("my.property")
  }

  def 'should resolve process tag from environment variable via {env} source'() {
    setup:
    ProcessTags.envGetter = key -> key == "MY_ENV_VAR" ? "myvalue" : null
    injectSysConfig("process.tags.mapping", "{env}MY_ENV_VAR:my_tag")
    ProcessTags.reset()
    when:
    def tags = ProcessTags.getTagsAsStringList()
    then:
    tags.contains("my_tag:myvalue")
    cleanup:
    ProcessTags.envGetter = System::getenv
    ProcessTags.reset()
  }

  def 'should not emit process tag when key is missing from source'() {
    setup:
    injectSysConfig("process.tags.mapping", "{prop}nonexistent.key:should_not_appear")
    ProcessTags.reset()
    when:
    def tags = ProcessTags.getTagsAsStringList()
    then:
    !tags.any { it.startsWith("should_not_appear:") }
  }

  def 'should not emit process tag when value is empty'() {
    setup:
    System.setProperty("empty.key", "")
    injectSysConfig("process.tags.mapping", "{prop}empty.key:should_not_appear")
    ProcessTags.reset()
    when:
    def tags = ProcessTags.getTagsAsStringList()
    then:
    !tags.any { it.startsWith("should_not_appear:") }
    cleanup:
    System.clearProperty("empty.key")
  }

  def 'should not emit process tag for unsupported source'() {
    setup:
    injectSysConfig("process.tags.mapping", "{unknown}some.key:should_not_appear")
    ProcessTags.reset()
    when:
    def tags = ProcessTags.getTagsAsStringList()
    then:
    !tags.any { it.startsWith("should_not_appear:") }
  }

  def 'should ignore malformed process tag mapping entries'() {
    setup:
    injectSysConfig("process.tags.mapping", malformed)
    ProcessTags.reset()
    when:
    def tags = ProcessTags.getTagsAsStringList()
    then:
    !tags.any { it.startsWith("bad_tag:") }
    where:
    // all three cases reach parseMappingEntry and trigger a WARN
    malformed                     | _
    "no_braces:key:bad_tag"       | _ // loadMap splits on first ':', key="no_braces" has no {source} prefix
    "{unclosed_brace key:bad_tag" | _ // key has a space, regex does not match
    "{prop}:bad_tag"              | _ // key is "{prop}" with no config-key after '}'
  }

  def 'last mapping wins when same source key is repeated'() {
    setup:
    System.setProperty("my.key", "first_value")
    // getMergedMap keeps the last value for the same map key
    injectSysConfig("process.tags.mapping", "{prop}my.key:tag_v1,{prop}my.key:tag_v2")
    ProcessTags.reset()
    when:
    def tags = ProcessTags.getTagsAsStringList()
    then:
    tags.contains("tag_v2:first_value")
    !tags.any { it.startsWith("tag_v1:") }
    cleanup:
    System.clearProperty("my.key")
  }

  def 'should handle multiple valid mapping entries'() {
    setup:
    System.setProperty("prop.a", "valueA")
    System.setProperty("prop.b", "valueB")
    injectSysConfig("process.tags.mapping", "{prop}prop.a:tag_a,{prop}prop.b:tag_b")
    ProcessTags.reset()
    when:
    def tags = ProcessTags.getTagsAsStringList()
    then:
    tags.contains("tag_a:valuea")
    tags.contains("tag_b:valueb")
    cleanup:
    System.clearProperty("prop.a")
    System.clearProperty("prop.b")
  }

  def 'process tag value normalization'() {
    setup:
    ProcessTags.addTag("test", testValue)
    expect:
    assert ProcessTags.tagsAsStringList != null
    assert ProcessTags.tagsAsStringList.find { it.startsWith("test:") } == "test:${expectedValue}"

    where:
    testValue                                          | expectedValue
    "#test_starting_hash"                              | "test_starting_hash"
    "TestCAPSandSuch"                                  | "testcapsandsuch"
    "Test Conversion Of Weird !@#\$%^&**() Characters" | "test_conversion_of_weird_characters"
    "\$#weird_starting"                                | "weird_starting"
    "disallowed:c0l0ns"                                | "disallowed_c0l0ns"
    "1love"                                            | "1love"
    "123456"                                           | "123456"
    "7.0"                                              | "7.0"
    "ünicöde"                                          | "ünicöde"
    "ünicöde:metäl"                                    | "ünicöde_metäl"
    "Data🐨dog🐶 繋がっ⛰てて"                            | "data_dog_繋がっ_てて"
    " spaces   "                                       | "spaces"
    " #hashtag!@#spaces #__<>#  "                      | "hashtag_spaces"
    ":testing"                                         | "testing"
    "_foo"                                             | "foo"
    ":::test"                                          | "test"
    "contiguous_____underscores"                       | "contiguous_underscores"
    "foo_"                                             | "foo"
    ""                                                 | ""
    " "                                                | ""
    "ok"                                               | "ok"
    "AlsO:ök"                                          | "also_ök"
    ":still_ok"                                        | "still_ok"
    "___trim"                                          | "trim"
    "fun:ky__tag/1"                                    | "fun_ky_tag/1"
    "fun:ky@tag/2"                                     | "fun_ky_tag/2"
    "fun:ky@@@tag/3"                                   | "fun_ky_tag/3"
    "tag:1/2.3"                                        | "tag_1/2.3"
    "---fun:k####y_ta@#g/1_@@#"                        | "fun_k_y_ta_g/1"
    "AlsO:œ#@ö))œk"                                    | "also_œ_ö_œk"
    " regulartag "                                     | "regulartag"
    "\u017Fodd_\u017Fcase\u017F"                       | "\u017Fodd_\u017Fcase\u017F"
    "™Ö™Ö™™Ö™"                                         | "ö_ö_ö"
    "a�"                                               | "a"
    "a��"                                              | "a"
    "a��b"                                             | "a_b"
  }
}
