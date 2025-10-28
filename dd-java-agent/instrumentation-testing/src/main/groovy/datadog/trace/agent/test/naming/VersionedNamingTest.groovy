package datadog.trace.agent.test.naming

interface VersionedNamingTest {
  int version()

  String service()

  String operation()
}
