import com.amazonaws.AmazonWebServiceRequest
import com.amazonaws.services.kinesis.AmazonKinesisClient
import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.instrumentation.api.AgentSpan
import datadog.trace.instrumentation.aws.v0.KinesisClientDecorator
import groovy.transform.Canonical

class ClientSpecificDecorationTest extends AgentTestRunner {
  @Canonical
  class ClientSpec {
    Class<?> clientClass
    String attributeName
    KinesisClientDecorator decorator
    String tagName
  }

  def "kinesis"() {
    expect:
    testClient(new ClientSpec(
      clientClass: AmazonKinesisClient,
      attributeName: "streamName",
      decorator: KinesisClientDecorator.DECORATE,
      tagName: "aws.stream.name"
    ))
  }

  def testClient(clientSpec) {
    // Scans a AWS Client class looking for methods that take a Request object
    // with the specified parameter

    // For each Request object that is discovered, constructs a Request object
    // and runs it through AwsSdkClientDecorator.onRequest to make sure the span
    // is tagged appropriately
    def found = false
    clientSpec.clientClass.methods.each { method ->
      def requestClass = requestClassOf(method)
      if (requestClass != null && hasAttr(clientSpec, requestClass)) {
        testRequestClass(clientSpec, requestClass)

        found = true
      }
    }
    return found
  }

  def testRequestClass(clientSpec, requestClass) {
    AmazonWebServiceRequest request = requestClass.newInstance()
    request[clientSpec.attributeName] = "a-test-value"

    def tags = new HashMap<String, Object>()
    def span = createMockSpan(tags)
    clientSpec.decorator.onOriginalRequest(span, request)

    def tag = tags.get(clientSpec.tagName)
    if (tag == null) {
      def requestClassName = requestClass.name
      def requestClassSimpleName = requestClass.simpleName
      def tagName = clientSpec.tagName
      def getterName = getterName(clientSpec.attributeName)

      def message = "Unhandled request type: ${requestClassSimpleName} - add...\n" +
        "import ${requestClassName};\n" +
        "if (originalRequest instanceof ${requestClassSimpleName}) {\n" +
        "  span.setTag(\"${tagName}\", (($requestClassSimpleName)originalRequest).${getterName}());\n" +
        "}"

      throw new IllegalStateException(message)
    }
    assert tags.get(clientSpec.tagName) == "a-test-value"
  }

  static requestClassOf(method) {
    Class<?>[] paramTypes = method.parameterTypes
    if (paramTypes.length != 1) {
      return null
    }

    Class<?> onlyParamType = paramTypes[0]
    if (!AmazonWebServiceRequest.isAssignableFrom(onlyParamType)) {
      return null
    }
    if (AmazonWebServiceRequest.equals(onlyParamType)) {
      return null
    }

    return onlyParamType
  }

  static hasAttr(clientSpec, klass) {
    def methodName = getterName(clientSpec.attributeName)

    try {
      klass.getMethod(methodName)
    } catch (NoSuchMethodException e) {
      return false
    }
  }

  static getterName(attrName) {
    return "get" + Character.toUpperCase(attrName.charAt(0)) + attrName.substring(1)
  }

  def createMockSpan(tagMap) {
    return new AgentSpan() {
      @Override
      AgentSpan setTag(String key, boolean value) {
        tagMap.set(key, value)
        return this
      }

      @Override
      AgentSpan setTag(String key, int value) {
        tagMap.put(key, value)
        return this
      }

      @Override
      AgentSpan setTag(String key, long value) {
        tagMap.put(key, value)
        return this
      }

      @Override
      AgentSpan setTag(String key, double value) {
        tagMap.put(key, value)
        return this
      }

      @Override
      AgentSpan setTag(String key, String value) {
        tagMap.put(key, value)
        return this
      }

      @Override
      AgentSpan setError(boolean error) {
        throw new UnsupportedOperationException()
      }

      @Override
      AgentSpan setErrorMessage(String errorMessage) {
        throw new UnsupportedOperationException()
      }

      @Override
      AgentSpan addThrowable(Throwable throwable) {
        throw new UnsupportedOperationException()
      }

      @Override
      AgentSpan getLocalRootSpan() {
        throw new UnsupportedOperationException()
      }

      @Override
      AgentSpan.Context context() {
        throw new UnsupportedOperationException()
      }

      @Override
      void finish() {}

      @Override
      String getSpanName() {
        return "span.name"
      }

      @Override
      void setSpanName(String spanName) {
        throw new UnsupportedOperationException()
      }
    }
  }
}
