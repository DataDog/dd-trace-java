/**
 * Extension points the Datadog Java agent fills in through instrumentation.
 *
 * <p>These types are not customer API. They form the contract between this SDK and the agent
 * instrumentation, which can run a different version than the SDK. Once released, this version of
 * the contract is frozen: only {@code default} methods can be added. A breaking change goes to a
 * new {@code bridge.v2} package, and the {@code japicmpBridge} checks enforce it.
 */
package com.datadog.openfeature.internal.bridge.v1;
