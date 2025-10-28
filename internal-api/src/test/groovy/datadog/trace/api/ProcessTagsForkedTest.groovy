package datadog.trace.api

import datadog.trace.api.env.CapturedEnvironment
import datadog.trace.test.util.DDSpecification

import java.nio.file.Paths

import static datadog.trace.api.config.GeneralConfig.EXPERIMENTAL_PROPAGATE_PROCESS_TAGS_ENABLED

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
    injectSysConfig(EXPERIMENTAL_PROPAGATE_PROCESS_TAGS_ENABLED, "true")
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

  def 'should load default tags jboss (mode #mode)'() {
    setup:
    injectSysConfig(EXPERIMENTAL_PROPAGATE_PROCESS_TAGS_ENABLED, "true")
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
    injectSysConfig(EXPERIMENTAL_PROPAGATE_PROCESS_TAGS_ENABLED, "true")
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

  def 'calculate process tags by default'() {
    when:
    ProcessTags.reset()
    def processTags = ProcessTags.tagsForSerialization
    then:
    assert ProcessTags.enabled
    assert (processTags != null)
    when:
    ProcessTags.addTag("test", "value")
    then:
    assert (ProcessTags.tagsForSerialization != null)
    assert (ProcessTags.tagsAsStringList != null)
    assert (ProcessTags.tagsAsUTF8ByteStringList != null)
  }

  def 'should lazily recalculate when a tag is added'() {
    setup:
    injectSysConfig(EXPERIMENTAL_PROPAGATE_PROCESS_TAGS_ENABLED, "true")
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
}
