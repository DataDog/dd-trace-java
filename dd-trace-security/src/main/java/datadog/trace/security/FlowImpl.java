package datadog.trace.security;

import lombok.extern.slf4j.Slf4j;

import java.util.Map;


@Slf4j
public class FlowImpl implements Flow {
    RuleAction ruleAction = RuleAction.noAction();

    private boolean shouldInterrupt;

    @Override
    public void recordAttack(EngineRule rule, Map<String, Object> infos) {
      // TODO
    }

    @Override
    public void block(EngineRule rule, Map<String, Object> infos) {
        if (!rule.isBlock()) {
            log.info("Rule %s cannot block; ignoring call to block()", rule);
            return;
        }

        log.info("Instruction to block from rule %s, infos %s", rule, infos);
        this.ruleAction = RuleAction.block(rule.getName(), infos);
        this.shouldInterrupt = true;
        // TODO: invoke block customization hook
    }

    @Override
    public boolean hasException() {
        return this.ruleAction.shouldThrow() != null;
    }

    @Override
    public Exception getException() {
        Exception exception = this.ruleAction.shouldThrow();
        if (exception == null) {
            throw new IllegalStateException("No exception");
        }
        return exception;
    }

    /**
     * @return true iif subsequent listener callbacks should be skipped
     */
    @Override
    public boolean shouldInterrupt() {
        return this.shouldInterrupt;
    }

    @Override
    public void mergeFrom(Flow rhs) {
        if (rhs.shouldInterrupt()) {
            this.shouldInterrupt = true;
        }

        FlowImpl fimpl = (FlowImpl) rhs; // TODO
        if (fimpl.ruleAction.priority() < ruleAction.priority()) {
            this.ruleAction = fimpl.ruleAction;
        }
    }

    public static class ImmutableFlow extends FlowImpl {
        public static final FlowImpl INSTANCE = new ImmutableFlow();
        private ImmutableFlow() {}

        @Override
        public void recordAttack(EngineRule rule, Map<String, Object> infos) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void block(EngineRule rule, Map<String, Object> infos) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void mergeFrom(Flow rhs) {
            throw new UnsupportedOperationException();
        }
    }
}
