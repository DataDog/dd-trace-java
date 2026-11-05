package datadog.perfcontract;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares that no class in the annotated top-level class's nest needs a synthetic accessor. The
 * nest is the top-level class and every class nested in it, including inner, local and anonymous
 * classes.
 *
 * <p>The tracer compiles to Java 8 bytecode, which has no nestmates. When one class of a nest uses
 * a {@code private} member of another, javac generates a static {@code access$NNN} method in the
 * class that declares the member, and the caller goes through it. Calling another nest member's
 * {@code private} constructor also generates a synthetic constructor, with an extra parameter typed
 * as a generated class. Each accessor is a real call in the interpreter and C1, and takes an
 * inlining level in C2, so on a hot path it can push a callee past {@code MaxInlineLevel}.
 * Accessors also add methods and classes to load at startup.
 *
 * <p>The fix is to make the member package-private, to stop using it across the nest, or, for a
 * member only used off the hot path, to suppress the contract on it. Which side the accessor lands
 * on doesn't matter to this contract: it covers the whole nest.
 *
 * <p>This marker changes no runtime behavior.
 *
 * <p><b>Checker contract.</b> Inspect the class files of the annotated class's nest:
 *
 * <ul>
 *   <li><b>Violation:</b> a synthetic method whose name starts with {@code access$}, or a synthetic
 *       constructor.
 *   <li><b>Not a violation:</b> other synthetic members, such as lambda bodies ({@code lambda$})
 *       and bridge methods.
 *   <li><b>Exemption:</b> an accessor whose target member, the field, method or constructor it
 *       reaches, carries {@link SuppressPerfContract} naming this contract, directly or through a
 *       reusable suppression annotation. Use it for members used across the nest only off the hot
 *       path, so they can stay {@code private}.
 * </ul>
 *
 * <p>The check is deterministic: tests call {@code
 * datadog.trace.test.util.SyntheticAccessors.assertNone} with the annotated class.
 */
@Documented
@PerfContract
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.TYPE)
public @interface NoSyntheticAccessors {}
