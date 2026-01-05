This is an instrumentation project.

In Mongo 3.6, the DefaultServerConnection was simplified to make all the queries type goes through the same code path when executing them. Because of this reason, the minimum version to have the DBM comment instrumentation is 3.6.
