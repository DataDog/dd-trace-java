package datadog.common.filesystem


import datadog.environment.JavaVirtualMachine
import datadog.trace.test.util.DDSpecification
import java.security.Permission
import spock.lang.IgnoreIf

class FilesTest extends DDSpecification {
  def "exists returns true when file exists and is accessible"() {
    given:
    def file = File.createTempFile("test", "txt")
    file.deleteOnExit()

    expect:
    Files.exists(file)
  }

  def "exists returns false when file does not exist"() {
    given:
    // Create a temp file and delete it to ensure path is valid but file is gone
    def file = File.createTempFile("missing", "txt")
    file.delete()

    expect:
    !Files.exists(file)
  }

  @IgnoreIf({
    JavaVirtualMachine.isJavaVersionAtLeast(18)
  })
  def "exists returns false when SecurityManager forbids file access"() {
    setup:
    def file = File.createTempFile("test", "txt")
    file.deleteOnExit()

    // install a restrictive security manager
    def originalSM = System.getSecurityManager()
    System.setSecurityManager(new SecurityManager() {
        @Override
        void checkRead(String filePath) {
          // Only deny THIS file otherwise we'll deny classloading as well and will result in a StackOverflowError
          if (filePath == file.absolutePath) {
            throw new SecurityException("Access denied")
          }
        }

        @Override
        void checkPermission(Permission perm) {
          // allow anything
        }

        @Override
        void checkPermission(Permission perm, Object context) {
          // allow anything
        }
      })

    when:
    def result = Files.exists(file)

    then:
    !result

    cleanup:
    System.setSecurityManager(originalSM)
  }
}
