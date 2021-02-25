package datadog.trace.core.sqreen;

/**
 * All limits are advisory. Setting limits implies making the result not cacheable.
 */
public final class BindingAccessorLimits {
    public final long maxDepth;  // currently unused
    public final long maxElements;
    public final int maxStringLength; // only used by uri decoding transformation

    public final static BindingAccessorLimits NO_LIMITS =
            new BindingAccessorLimits(Long.MAX_VALUE, Long.MAX_VALUE, Integer.MAX_VALUE);

    public BindingAccessorLimits(long maxDepth, long maxElements, int maxStringLength) {
        this.maxDepth = maxDepth;
        this.maxElements = maxElements;
        this.maxStringLength = maxStringLength;
    }
}
