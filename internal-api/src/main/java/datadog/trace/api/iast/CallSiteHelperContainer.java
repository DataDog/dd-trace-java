package datadog.trace.api.iast;

/**
 * A marker interface for static methods annotated with {@link CallSiteHelper}. XXX: because of the
 * usage of autoservice, the implementations must be default constructible and annotated
 * with @AutoService(CallSiteHelperContainer.class) as well
 */
public interface CallSiteHelperContainer {}
