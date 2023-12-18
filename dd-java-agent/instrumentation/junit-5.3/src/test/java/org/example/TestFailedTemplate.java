package org.example;

import static java.util.Arrays.asList;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.Extension;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolutionException;
import org.junit.jupiter.api.extension.ParameterResolver;
import org.junit.jupiter.api.extension.TestTemplateInvocationContext;
import org.junit.jupiter.api.extension.TestTemplateInvocationContextProvider;

public class TestFailedTemplate {

  @org.junit.jupiter.api.TestTemplate
  @ExtendWith(SampleInvocationContextProvider.class)
  public void test_template(final SampleTestCase testCase) {
    assertEquals(testCase.result, testCase.a + testCase.b);
  }

  public static class SampleTestCase {
    private final String displayName;
    private final int a;
    private final int b;
    private final int result;

    public SampleTestCase(final String displayName, final int a, final int b, final int result) {
      this.displayName = displayName;
      this.a = a;
      this.b = b;
      this.result = result;
    }
  }

  public static class SampleInvocationContextProvider
      implements TestTemplateInvocationContextProvider {

    @Override
    public boolean supportsTestTemplate(final ExtensionContext context) {
      return true;
    }

    @Override
    public Stream<TestTemplateInvocationContext> provideTestTemplateInvocationContexts(
        final ExtensionContext context) {
      return Stream.of(
          featureEnabledContext(new SampleTestCase("test_template_failed", 0, 0, 42)),
          featureEnabledContext(new SampleTestCase("test_template_succeed", 1, 1, 2)));
    }

    private TestTemplateInvocationContext featureEnabledContext(
        final SampleTestCase sampleTestCase) {
      return new TestTemplateInvocationContext() {
        @Override
        public String getDisplayName(final int invocationIndex) {
          return sampleTestCase.displayName;
        }

        @Override
        public List<Extension> getAdditionalExtensions() {
          return asList(new GenericTypedParameterResolver(sampleTestCase));
        }
      };
    }
  }

  public static class GenericTypedParameterResolver<T> implements ParameterResolver {
    T data;

    public GenericTypedParameterResolver(final T data) {
      this.data = data;
    }

    @Override
    public boolean supportsParameter(
        final ParameterContext parameterContext, final ExtensionContext extensionContext)
        throws ParameterResolutionException {
      return parameterContext.getParameter().getType().isInstance(data);
    }

    @Override
    public Object resolveParameter(
        final ParameterContext parameterContext, final ExtensionContext extensionContext)
        throws ParameterResolutionException {
      return data;
    }
  }
}
