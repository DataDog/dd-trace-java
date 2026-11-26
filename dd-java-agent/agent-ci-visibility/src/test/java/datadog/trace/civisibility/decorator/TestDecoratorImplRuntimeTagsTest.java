package datadog.trace.civisibility.decorator;

import static datadog.trace.api.config.CiVisibilityConfig.TEST_OPTIMIZATION_RUNTIME_TAGS;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.test.junit.utils.config.WithConfigExtension;
import java.util.Collections;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;

@ExtendWith(WithConfigExtension.class)
class TestDecoratorImplRuntimeTagsTest {

  @Test
  void setsRuntimeTagOverridesAfterTheCiTags() {
    WithConfigExtension.injectSysConfig(
        TEST_OPTIMIZATION_RUNTIME_TAGS, "{\"os.version\":\"6.12.0\"}");
    TestDecoratorImpl decorator =
        new TestDecoratorImpl(
            "test-component",
            "session-name",
            "test-command",
            Collections.singletonMap(Tags.OS_VERSION, "detected"));
    AgentSpan span = mock(AgentSpan.class, RETURNS_DEEP_STUBS);

    decorator.afterStart(span);

    InOrder order = inOrder(span);
    order.verify(span).setTag(Tags.OS_VERSION, "detected");
    order.verify(span).setTag(Tags.OS_VERSION, "6.12.0");
  }
}
