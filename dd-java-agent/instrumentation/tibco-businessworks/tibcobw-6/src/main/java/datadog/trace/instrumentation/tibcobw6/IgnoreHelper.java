package datadog.trace.instrumentation.tibcobw6;

import com.tibco.bx.core.behaviors.activity.BxEmptyBehavior;
import com.tibco.bx.core.behaviors.activity.BxFlowBehavior;
import com.tibco.bx.core.behaviors.activity.BxPickBehavior;
import com.tibco.bx.core.behaviors.activity.BxScopeBehavior;
import java.util.HashSet;
import java.util.Set;
import javax.annotation.Nonnull;

public class IgnoreHelper {
  private static final Set<Class<?>> IGNORED_TRACE_CLASSES = init();

  private static Set<Class<?>> init() {
    HashSet<Class<?>> ret = new HashSet<>();
    ret.add(BxFlowBehavior.class);
    ret.add(BxScopeBehavior.class);
    ret.add(BxEmptyBehavior.class);
    ret.add(BxPickBehavior.class);
    return ret;
  }

  public static boolean notTracing(@Nonnull final Object o) {
    return IGNORED_TRACE_CLASSES.contains(o.getClass());
  }
}
