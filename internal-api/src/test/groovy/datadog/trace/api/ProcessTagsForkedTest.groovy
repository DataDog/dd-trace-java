package datadog.trace.api

import datadog.trace.api.env.CapturedEnvironment
import datadog.trace.test.util.DDSpecification

import java.nio.file.Paths

class ProcessTagsForkedTest extends DDSpecification {

  def originalProcessInfo

  def setup() {
    originalProcessInfo = CapturedEnvironment.get().getProcessInfo()
  }

  def cleanup() {
    CapturedEnvironment.useFixedProcessInfo(originalProcessInfo)
    ProcessTags.reset()
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
    Paths.get("my test", "my.jar").toFile() | null            | "entrypoint.name:my,entrypoint.basedir:my_test,entrypoint.workdir:[^,]+"
    Paths.get("my.jar").toFile()            | null            | "entrypoint.name:my,entrypoint.workdir:[^,]+"
    null                                    | "com.test.Main" | "entrypoint.name:com.test.main,entrypoint.workdir:[^,]+"
    null                                    | null            | "entrypoint.workdir:[^,]+"
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
    "/opt/jboss/myserver" | "[Standalone]"    | "standalone" | "entrypoint.name:jboss-modules,entrypoint.basedir:somewhere,entrypoint.workdir:.+,jboss.home:myserver,server.name:standalone,jboss.mode:standalone"
    "/opt/jboss/myserver" | "[server1:12345]" | "server1"    | "entrypoint.name:jboss-modules,entrypoint.basedir:somewhere,entrypoint.workdir:.+,jboss.home:myserver,server.name:server1,jboss.mode:domain"
    null                  | "[Standalone]"    | "standalone" | "entrypoint.name:jboss-modules,entrypoint.basedir:somewhere,entrypoint.workdir:[^,]+" // don't expect jboss tags since home is missing


  }
}
