This is an instrumentation project.

This project provides instrumentation for `com.mongodb.connection.ByteBufBsonDocument` required by `driver-3.1` project.
This instrumentation may not be included in the `driver-3.1` since it breaks muzzle check there - the client API classes are changing between Mongo 3.1 and Mongo 3.4 and having this instrumentation extracted makes it manageable.

The instrumentation is tested in `driver-3.1` project.
