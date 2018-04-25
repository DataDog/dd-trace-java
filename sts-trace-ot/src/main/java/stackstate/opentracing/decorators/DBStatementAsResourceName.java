package stackstate.opentracing.decorators;

import io.opentracing.tag.Tags;
import stackstate.opentracing.STSSpanContext;
import stackstate.trace.api.STSTags;

public class DBStatementAsResourceName extends AbstractDecorator {

  public DBStatementAsResourceName() {
    super();
    this.setMatchingTag(Tags.DB_STATEMENT.getKey());
    this.setSetTag(STSTags.RESOURCE_NAME);
  }

  @Override
  public boolean afterSetTag(final STSSpanContext context, final String tag, final Object value) {

    // Special case: Mongo
    // Skip the decorators
    if (context.getTags().containsKey(Tags.COMPONENT.getKey())
        && "java-mongo".equals(context.getTags().get(Tags.COMPONENT.getKey()))) {
      return true;
    }

    // Assign service name
    if (super.afterSetTag(context, tag, value)) {
      // TODO: remove properly the tag (immutable at this time)
      // the `db.statement` tag must be removed because it will be set
      // by the StackState Trace Agent as `sql.query`; here we're removing
      // a duplicate that will not be obfuscated with the current StackState
      // Trace Agent version.
      context.setTag(Tags.DB_STATEMENT.getKey(), null);
      return true;
    }
    return false;
  }
}
