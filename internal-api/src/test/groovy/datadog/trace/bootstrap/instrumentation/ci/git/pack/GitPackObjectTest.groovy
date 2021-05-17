package datadog.trace.bootstrap.instrumentation.ci.git.pack

import datadog.trace.test.util.DDSpecification

class GitPackObjectTest extends DDSpecification {

  def "test gitpack object"() {
    setup:
    def testShaIndex = 1
    def testType = (byte) 0
    def testContent = new byte[0]
    def testErrorFlag = true

    when:
    def obj = new GitPackObject(testShaIndex, testType, testContent, testErrorFlag)

    then:
    obj.shaIndex == testShaIndex
    obj.deflatedContent == testContent
    obj.raisedError() == testErrorFlag
  }
}
