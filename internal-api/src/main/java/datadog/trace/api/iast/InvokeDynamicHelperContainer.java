package datadog.trace.api.iast;

import java.lang.invoke.MethodHandles;

/**
 * A marker interface for static methods annotated with {@link InvokeDynamicHelper}.
 *
 * <p>Implementing classes must be annotated with <code>
 * @AutoService(InvokeDynamicHelperContainer.class)</code> to be registered at runtime (or else
 * {@link InvokeDynamicHelperRegistry#registerHelperContainer(MethodHandles.Lookup, Class)} must be
 * explicitly called.
 *
 * <p>The plugin <code>InvokeDynamicHelperUsersPlugin</code> looks for certain potential users of
 * annotated methods of implementors of this interface and replaces static calls with invokedynamic
 * calls.
 */
public interface InvokeDynamicHelperContainer {}
