# Data-streams close signalling

Checks that `DefaultDataStreamsMonitoring.close()` cannot return while its
worker remains alive. With a full 1024-element inbox, the non-blocking poison
offer fails and the worker has no stop signal. TLC produces a three-state
counterexample.

The corresponding regression is
`DefaultDataStreamsMonitoringTest.closeStopsWorkerWhenInboxIsFull`.
