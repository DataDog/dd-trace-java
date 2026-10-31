# Telemetry retry ownership

Models one event through the primary queue, request construction, retry
buffer, and the current bounded retry policy. It checks that accepted telemetry
always has an owner or an explicit terminal outcome, and that delivery and
policy-drop are mutually exclusive.

This model passes. The `policyDropped` state documents the existing behavior
where events are discarded after the buffered retry also fails.
