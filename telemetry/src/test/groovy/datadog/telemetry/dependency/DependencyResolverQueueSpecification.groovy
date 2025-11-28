package datadog.telemetry.dependency

class DependencyResolverQueueSpecification extends DepSpecification {

  DependencyResolverQueue resolverQueue = new DependencyResolverQueue()

  void 'resolve set of dependencies'() {
    when:
    resolverQueue.queueURI(getJar('junit-4.12.jar').toURI())
    resolverQueue.queueURI(getJar('asm-util-9.2.jar').toURI())
    resolverQueue.queueURI(getJar('bson-4.2.0.jar').toURI())

    and:
    def dep = resolverQueue.pollDependency().get(0)

    then:
    assert dep != null
    assert dep.name == 'junit'
    assert dep.version == '4.12'
    assert dep.source == 'junit-4.12.jar'
    assert dep.hash == '4376590587C49AC6DA6935564233F36B092412AE'

    when:
    dep = resolverQueue.pollDependency().get(0)

    then:
    assert dep != null
    assert dep.name == 'asm-util'
    assert dep.version == '9.2'
    assert dep.source == 'asm-util-9.2.jar'
    assert dep.hash == '9A5AEC2CB852B8BD20DAF5D2CE9174891267FE27'

    when:
    dep = resolverQueue.pollDependency().get(0)

    then:
    assert dep != null
    assert dep.name == 'org.mongodb:bson'
    assert dep.version == '4.2.0'
    assert dep.source == 'bson-4.2.0.jar'
    assert dep.hash == 'F87C3A90DA4BB1DA6D3A73CA18004545AD2EF06A'

    when:
    def deps = resolverQueue.pollDependency()

    then:
    assert deps.isEmpty()

    when: 'a repeated dependency is added'
    resolverQueue.queueURI(getJar('junit-4.12.jar').toURI())
    deps = resolverQueue.pollDependency()

    then: 'it has no effect'
    assert deps.isEmpty()
  }

  void 'resolve set of dependencies with queue limit'() {
    when:
    resolverQueue = new DependencyResolverQueue(3)
    resolverQueue.queueURI(getJar('junit-4.12.jar').toURI())
    resolverQueue.queueURI(getJar('asm-util-9.2.jar').toURI())
    resolverQueue.queueURI(getJar('bson-4.2.0.jar').toURI())
    // this dependency should be dropped
    resolverQueue.queueURI(getJar('commons-logging-1.2.jar').toURI())

    and:
    def dep = resolverQueue.pollDependency().get(0)

    then:
    assert dep != null
    assert dep.name == 'junit'
    assert dep.version == '4.12'
    assert dep.source == 'junit-4.12.jar'
    assert dep.hash == '4376590587C49AC6DA6935564233F36B092412AE'

    when:
    dep = resolverQueue.pollDependency().get(0)

    then:
    assert dep != null
    assert dep.name == 'asm-util'
    assert dep.version == '9.2'
    assert dep.source == 'asm-util-9.2.jar'
    assert dep.hash == '9A5AEC2CB852B8BD20DAF5D2CE9174891267FE27'

    when:
    dep = resolverQueue.pollDependency().get(0)

    then:
    assert dep != null
    assert dep.name == 'org.mongodb:bson'
    assert dep.version == '4.2.0'
    assert dep.source == 'bson-4.2.0.jar'
    assert dep.hash == 'F87C3A90DA4BB1DA6D3A73CA18004545AD2EF06A'

    when:
    def deps = resolverQueue.pollDependency()

    then:
    assert deps.isEmpty()
  }
}
