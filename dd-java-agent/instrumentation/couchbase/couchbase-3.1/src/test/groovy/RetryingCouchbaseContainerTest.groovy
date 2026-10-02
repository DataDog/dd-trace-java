import org.testcontainers.couchbase.CouchbaseContainer
import spock.lang.Specification

class RetryingCouchbaseContainerTest extends Specification {
  def "retries only transient failures from node renaming"() {
    given:
    def failure = new RuntimeException(cause)
    failure.stackTrace = [new StackTraceElement(CouchbaseContainer.name, method, 'CouchbaseContainer.java', 1)]

    expect:
    RetryingCouchbaseContainer.isTransientRenameFailure(failure) == retry

    where:
    cause                      | method               | retry
    new EOFException()         | 'renameNode'         | true
    new SocketException()      | 'renameNode'         | true
    new IOException()          | 'initializeServices' | false
    new IllegalStateException() | 'renameNode'         | false
  }
}
