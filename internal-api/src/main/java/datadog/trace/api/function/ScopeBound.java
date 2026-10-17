package datadog.trace.api.function;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a type whose instances are valid only inside the call that handed them out: never store
 * one, capture one, or hand one to another thread. The scope that created the instance holds
 * something the instance relies on -- a lock, typically -- and releases it when the call returns.
 *
 * <p>The motivating case is {@code TagsWriter}: {@code AgentSpan.setTagsFrom} holds the span's tag
 * lock while a contributor or extractor writes through it, so the writer itself takes no lock. A
 * writer used after {@code setTagsFrom} returns, or from another thread while it runs, writes to
 * the span's tags with no lock held: a data race with the thread that serializes the span.
 *
 * <p>This is a <b>correctness</b> contract, and the rule is "must", not "should". That is what sets
 * it apart from {@link NoEscape}, which has a similar shape for performance reasons (a retained
 * value pins memory or defeats escape analysis) and allows a reviewed exception. No reason makes
 * retaining a scope-bound instance safe, so there is no exemption: {@link SuppressPerfContract}
 * does not apply, and this is deliberately not a {@link PerfContract}.
 *
 * <p>The scope that hands an instance out should check the rule at run time where checking is cheap
 * -- for {@code TagsWriter}, a checked writer under strict trace writes, as in tests -- so misuse
 * fails loudly in tests even before a static checker exists.
 *
 * <p>Retention is {@link RetentionPolicy#CLASS}, as for {@link NoEscape}: a checker of a dependent
 * module needs to see it in compiled classes.
 *
 * <p><b>Checker contract.</b> A trigger is a violation, not a presumptive finding: there is no
 * suppression.
 *
 * <ul>
 *   <li><b>Trigger (storage):</b> a field (instance or static, in any class) whose declared type is
 *       annotated {@code @ScopeBound}, directly or as a generic type argument (e.g. {@code
 *       List<TagsWriter>}).
 *   <li><b>Trigger (capture):</b> a lambda, method reference, anonymous class, or local class that
 *       captures a variable or parameter whose type is annotated {@code @ScopeBound}.
 *   <li><b>Not a trigger:</b> a local variable, a method parameter, a return type, or passing the
 *       instance as an argument to another method within the scope -- the rule is about outliving
 *       the scope or leaving its thread, not about how many frames the instance crosses.
 *   <li><b>Violation example:</b> {@code private TagsWriter writer;}, or {@code executor.execute(()
 *       -> writer.setTag(id, value));} inside {@code addTo}.
 *   <li><b>Compliant example:</b> {@code public void addTo(TagsWriter writer) {
 *       writeDbTags(writer); }}, where {@code writeDbTags} uses the writer and returns.
 *   <li><b>Out of scope (v1):</b> passing the instance to a method that itself stores or captures
 *       it; that method's own body is where the trigger fires.
 * </ul>
 */
@Documented
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.TYPE)
public @interface ScopeBound {}
