package datadog.trace.security;

import java.util.Map;

public abstract class RuleAction {

    public Exception shouldThrow() {
        return null;
    }

    public abstract int priority();

    private final static class NoAction extends RuleAction {
        @Override
        public int priority() {
            return Integer.MAX_VALUE; /* lowest priority */
        }
    };
    private final static RuleAction NO_ACTION = new NoAction();
    public static RuleAction noAction() {
        return NO_ACTION;
    }

    private static class BlockAction extends RuleAction {
        final BlockedBySqreenAdviceException exception;
        BlockAction(String ruleName, Map<String, Object> additionalData) {
            this.exception = new BlockedBySqreenAdviceException(ruleName, additionalData);
        }

        @Override
        public Exception shouldThrow() {
            return this.exception;
        }

        @Override
        public int priority() {
            return Integer.MIN_VALUE; /* lowest priority */
        }
    }
    
    public static RuleAction block(String ruleName, Map<String, Object> additionalData) {
        return new BlockAction(ruleName, additionalData);
    }
}
