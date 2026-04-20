This is an instrumentation project.

Mongo has several client libraries and they can not be selectively used in one project.

In Mongo 4.0 the mongodb-driver-reactivestreams module added its own work queue which needs to be instrumented to propagate trace information.

This project is running tests using the `mongodb-driver-sync` and `mongodb-driver-reactivestreams`, version 4.0-4.2.
