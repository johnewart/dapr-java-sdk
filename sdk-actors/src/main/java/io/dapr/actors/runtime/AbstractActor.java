/*
 * Copyright 2021 The Dapr Authors
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *     http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
limitations under the License.
*/

package io.dapr.actors.runtime;

import io.dapr.actors.ActorId;
import io.dapr.actors.ActorTrace;
import io.dapr.actors.runtime.reentrancy.ReentrancyStack;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.time.Duration;
import java.util.UUID;

/**
 * Represents the base class for actors.
 * The base type for actors, that provides the common functionality for actors.
 * The state is preserved across actor garbage collections and fail-overs.
 */
public abstract class AbstractActor {

  private static final ActorObjectSerializer INTERNAL_SERIALIZER = new ActorObjectSerializer();

  /**
   * Type of tracing messages.
   */
  private static final String TRACE_TYPE = "Actor";

  /**
   * Context for the Actor runtime.
   */
  private final ActorRuntimeContext<?> actorRuntimeContext;

  /**
   * Actor identifier.
   */
  private final ActorId id;

  /**
   * Emits trace messages for Actors.
   */
  private final ActorTrace actorTrace;

  /**
   * Manager for the states in Actors.
   */
  private final ActorStateManager actorStateManager;

  /**
   * Internal control to assert method invocation on start and finish in this SDK.
   */
  private ReentrancyStack reentrancyStack;

  /**
   * Instantiates a new Actor.
   *
   * @param runtimeContext Context for the runtime.
   * @param id             Actor identifier.
   */
  protected AbstractActor(ActorRuntimeContext runtimeContext, ActorId id) {
    this.actorRuntimeContext = runtimeContext;
    this.id = id;
    this.actorStateManager = new ActorStateManager(
          runtimeContext.getStateProvider(),
          runtimeContext.getActorTypeInformation().getName(),
          id);
    this.actorTrace = runtimeContext.getActorTrace();
    this.reentrancyStack = new ReentrancyStack();
  }

  /**
   * Returns the id of the actor.
   *
   * @return Actor id.
   */
  protected ActorId getId() {
    return this.id;
  }

  /**
   * Returns the actor's type.
   *
   * @return Actor type.
   */
  String getType() {
    return this.actorRuntimeContext.getActorTypeInformation().getName();
  }

  /**
   * Returns the state store manager for this Actor.
   *
   * @return State store manager for this Actor
   */
  protected ActorStateManager getActorStateManager() {
    return this.actorStateManager;
  }

  /**
   * Registers a reminder for this Actor.
   *
   * @param reminderName Name of the reminder.
   * @param state        State to be send along with reminder triggers.
   * @param dueTime      Due time for the first trigger.
   * @param period       Frequency for the triggers.
   * @param <T>          Type of the state object.
   * @return Asynchronous void response.
   */
  protected <T> Mono<Void> registerReminder(
        String reminderName,
        T state,
        Duration dueTime,
        Duration period) {
    try {
      byte[] data = this.actorRuntimeContext.getObjectSerializer().serialize(state);
      ActorReminderParams params = new ActorReminderParams(data, dueTime, period);
      return this.actorRuntimeContext.getDaprClient().registerReminder(
            this.actorRuntimeContext.getActorTypeInformation().getName(),
            this.id.toString(),
            reminderName,
            params);
    } catch (IOException e) {
      return Mono.error(e);
    }
  }

  /**
   * Registers a Timer for the actor. A timer name is autogenerated by the runtime to keep track of it.
   *
   * @param timerName Name of the timer, unique per Actor (auto-generated if null).
   * @param callback  Name of the method to be called.
   * @param state     State to be passed it to the method when timer triggers.
   * @param dueTime   The amount of time to delay before the async callback is first invoked.
   *                  Specify negative one (-1) milliseconds to prevent the timer from starting.
   *                  Specify zero (0) to start the timer immediately.
   * @param period    The time interval between invocations of the async callback.
   *                  Specify negative one (-1) milliseconds to disable periodic signaling.
   * @param <T>       Type for the state to be passed in to timer.
   * @return Asynchronous result with timer's name.
   */
  protected <T> Mono<String> registerActorTimer(
        String timerName,
        String callback,
        T state,
        Duration dueTime,
        Duration period) {
    try {
      if ((callback == null) || callback.isEmpty()) {
        throw new IllegalArgumentException("Timer requires a callback function.");
      }

      String name = timerName;
      if ((timerName == null) || (timerName.isEmpty())) {
        name = String.format("%s_Timer_%s", this.id.toString(), UUID.randomUUID().toString());
      }

      byte[] data = this.actorRuntimeContext.getObjectSerializer().serialize(state);
      ActorTimerParams actorTimer = new ActorTimerParams(callback, data, dueTime, period);

      return this.actorRuntimeContext.getDaprClient().registerTimer(
          this.actorRuntimeContext.getActorTypeInformation().getName(),
          this.id.toString(),
          name,
          actorTimer).thenReturn(name);
    } catch (Exception e) {
      return Mono.error(e);
    }
  }

  /**
   * Unregisters an Actor timer.
   *
   * @param timerName Name of Timer to be unregistered.
   * @return Asynchronous void response.
   */
  protected Mono<Void> unregisterTimer(String timerName) {
    return this.actorRuntimeContext.getDaprClient().unregisterTimer(
                this.actorRuntimeContext.getActorTypeInformation().getName(),
                this.id.toString(),
                timerName);
  }

  /**
   * Unregisters a Reminder.
   *
   * @param reminderName Name of Reminder to be unregistered.
   * @return Asynchronous void response.
   */
  protected Mono<Void> unregisterReminder(String reminderName) {
    return this.actorRuntimeContext.getDaprClient().unregisterReminder(
          this.actorRuntimeContext.getActorTypeInformation().getName(),
          this.id.toString(),
          reminderName);
  }

  /**
   * Callback function invoked after an Actor has been activated.
   *
   * @return Asynchronous void response.
   */
  protected Mono<Void> onActivate() {
    return Mono.empty();
  }

  /**
   * Callback function invoked after an Actor has been deactivated.
   *
   * @return Asynchronous void response.
   */
  protected Mono<Void> onDeactivate() {
    return Mono.empty();
  }

  /**
   * Callback function invoked before method is invoked.
   *
   * @param actorMethodContext Method context.
   * @return Asynchronous void response.
   */
  protected Mono<Void> onPreActorMethod(ActorMethodContext actorMethodContext) {
    return Mono.empty();
  }

  /**
   * Callback function invoked after method is invoked.
   *
   * @param actorMethodContext Method context.
   * @return Asynchronous void response.
   */
  protected Mono<Void> onPostActorMethod(ActorMethodContext actorMethodContext) {
    return Mono.empty();
  }

  /**
   * Saves the state of this Actor.
   *
   * @return Asynchronous void response.
   */
  protected Mono<Void> saveState() {
    return this.actorStateManager.save();
  }

  /**
   * Resets the cached state of this Actor.
   */
  void rollback(final ActorMethodContext context) {
    if (this.reentrancyStack.isOpen(context.getReentrancyId())) {
      throw new IllegalStateException("Cannot reset state before starting call.");
    }

    this.resetState();
    this.reentrancyStack.endOrDecrementStack(context.getReentrancyId());
  }

  /**
   * Resets the cached state of this Actor.
   */
  void resetState() {
    this.actorStateManager.clear();
  }

  /**
   * Internal callback when an Actor is activated.
   *
   * @return Asynchronous void response.
   */
  Mono<Void> onActivateInternal() {
    return Mono.fromRunnable(() -> {
      this.actorTrace.writeInfo(TRACE_TYPE, this.id.toString(), "Activating ...");
      this.resetState();
    }).then(this.onActivate())
          .then(this.doWriteInfo(TRACE_TYPE, this.id.toString(), "Activated"))
          .then(this.saveState());
  }

  /**
   * Internal callback when an Actor is deactivated.
   *
   * @return Asynchronous void response.
   */
  Mono<Void> onDeactivateInternal() {
    this.actorTrace.writeInfo(TRACE_TYPE, this.id.toString(), "Deactivating ...");

    return Mono.fromRunnable(() -> this.resetState())
          .then(this.onDeactivate())
          .then(this.doWriteInfo(TRACE_TYPE, this.id.toString(), "Deactivated"));
  }

  /**
   * Internal callback prior to method be invoked.
   *
   * @param actorMethodContext Method context.
   * @return Asynchronous void response.
   */
  Mono<Void> onPreActorMethodInternal(ActorMethodContext actorMethodContext) {
    return Mono.fromRunnable(() -> {
      if (!this.reentrancyStack.isOpen(actorMethodContext.getReentrancyId())) {
        throw new IllegalStateException("Cannot invoke a method before completing previous call.");
      }

      this.reentrancyStack.startOrIncreaseStack(actorMethodContext.getReentrancyId());
    }).then(this.onPreActorMethod(actorMethodContext));
  }

  /**
   * Internal callback after method is invoked.
   *
   * @param actorMethodContext Method context.
   * @return Asynchronous void response.
   */
  Mono<Void> onPostActorMethodInternal(ActorMethodContext actorMethodContext) {
    return Mono.fromRunnable(() -> {
      if (!this.reentrancyStack.inProgress()) {
        throw new IllegalStateException("Cannot complete a method before starting a call.");
      }
    }).then(this.onPostActorMethod(actorMethodContext))
          .then(this.saveState())
          .then(Mono.fromRunnable(() -> {
            this.reentrancyStack.endOrDecrementStack(actorMethodContext.getReentrancyId());
          }));
  }

  /**
   * Internal method to emit a trace message.
   *
   * @param type    Type of trace message.
   * @param id      Identifier of entity relevant for the trace message.
   * @param message Message to be logged.
   * @return Asynchronous void response.
   */
  private Mono<Void> doWriteInfo(String type, String id, String message) {
    return Mono.fromRunnable(() -> this.actorTrace.writeInfo(type, id, message));
  }

}
