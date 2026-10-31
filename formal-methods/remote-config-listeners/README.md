# Remote-config listener distribution

Checks that caching a target means every healthy listener received it, or that
a retry remains pending. The current generic-exception path catches around the
entire listener loop, caches the failed target, and suppresses retries for an
identical response. TLC produces a three-state counterexample.

The corresponding regression is
`ProductStateSpecification.listenerFailureDoesNotPreventLaterListenersFromReceivingConfiguration`.
