package datadog.trace.bootstrap.instrumentation.decorator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import datadog.trace.api.DDTags;
import datadog.trace.api.TagMap;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import org.junit.jupiter.api.Test;

class SpanPrototypeTest {

  @Test
  void serverPrototypeComposesBaseAndServerConstants() {
    final TagMap tags = new TestServerDecorator().prototype().tags();

    // BaseDecorator contribution
    assertEquals("test-component", tags.getString(Tags.COMPONENT));
    // ServerDecorator contribution
    assertEquals(Tags.SPAN_KIND_SERVER, tags.getString(Tags.SPAN_KIND));
    assertEquals(DDTags.LANGUAGE_TAG_VALUE, tags.getString(DDTags.LANGUAGE_TAG_KEY));
  }

  @Test
  void clientPrototypeComposesBaseAndClientConstants() {
    final TagMap tags = new TestClientDecorator().prototype().tags();

    assertEquals("test-component", tags.getString(Tags.COMPONENT));
    assertEquals(Tags.SPAN_KIND_CLIENT, tags.getString(Tags.SPAN_KIND));
  }

  @Test
  void prototypeIsBakedOnce() {
    final TestServerDecorator decorator = new TestServerDecorator();
    assertSame(decorator.prototype(), decorator.prototype());
  }

  static final class TestServerDecorator extends ServerDecorator {
    @Override
    protected String[] instrumentationNames() {
      return new String[] {"test"};
    }

    @Override
    protected CharSequence spanType() {
      return "test-type";
    }

    @Override
    protected CharSequence component() {
      return "test-component";
    }
  }

  static final class TestClientDecorator extends ClientDecorator {
    @Override
    protected String[] instrumentationNames() {
      return new String[] {"test"};
    }

    @Override
    protected CharSequence spanType() {
      return "test-type";
    }

    @Override
    protected CharSequence component() {
      return "test-component";
    }

    @Override
    protected String service() {
      return "test-service";
    }
  }
}
