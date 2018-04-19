package stackstate.opentracing.decorators;

import io.opentracing.tag.Tags;
import stackstate.opentracing.DDSpanContext;

public class ErrorFlag extends AbstractDecorator {
  public ErrorFlag() {
    super();
    this.setMatchingTag(Tags.ERROR.getKey());
  }

  @Override
  public boolean afterSetTag(final DDSpanContext context, final String tag, final Object value) {
    // Assign resource name
    try {
      context.setErrorFlag(Boolean.parseBoolean(String.valueOf(value)));
    } catch (final Throwable t) {
      // DO NOTHING
    }
    return true;
  }
}
