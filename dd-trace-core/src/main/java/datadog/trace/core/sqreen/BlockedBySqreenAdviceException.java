package datadog.trace.core.sqreen;

public class BlockedBySqreenAdviceException extends RuntimeException {
    private final String ruleName;
    private final Object /* map? */ additionalData;

    public BlockedBySqreenAdviceException(String ruleName, Object additionalData) {
        super("Blocked by Sqreen");
        this.ruleName = ruleName;
        this.additionalData = additionalData;
    }

    public String getRuleName() {
        return ruleName;
    }

    public Object getAdditionalData() {
        return additionalData;
    }
}
