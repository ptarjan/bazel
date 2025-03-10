// Copyright 2018 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.google.devtools.build.lib.dynamic;

import static com.google.common.base.Preconditions.checkState;
import static com.google.devtools.build.lib.actions.DynamicStrategyRegistry.DynamicMode.LOCAL;
import static com.google.devtools.build.lib.actions.DynamicStrategyRegistry.DynamicMode.REMOTE;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableList;
import com.google.common.flogger.GoogleLogger;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.SettableFuture;
import com.google.devtools.build.lib.actions.ActionContext;
import com.google.devtools.build.lib.actions.ActionExecutionContext;
import com.google.devtools.build.lib.actions.DynamicStrategyRegistry;
import com.google.devtools.build.lib.actions.DynamicStrategyRegistry.DynamicMode;
import com.google.devtools.build.lib.actions.EnvironmentalExecException;
import com.google.devtools.build.lib.actions.ExecException;
import com.google.devtools.build.lib.actions.ExecutionRequirements;
import com.google.devtools.build.lib.actions.SandboxedSpawnStrategy;
import com.google.devtools.build.lib.actions.Spawn;
import com.google.devtools.build.lib.actions.SpawnResult;
import com.google.devtools.build.lib.actions.SpawnStrategy;
import com.google.devtools.build.lib.actions.UserExecException;
import com.google.devtools.build.lib.dynamic.DynamicExecutionModule.IgnoreFailureCheck;
import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.exec.ExecutionPolicy;
import com.google.devtools.build.lib.server.FailureDetails;
import com.google.devtools.build.lib.server.FailureDetails.DynamicExecution;
import com.google.devtools.build.lib.server.FailureDetails.DynamicExecution.Code;
import com.google.devtools.build.lib.server.FailureDetails.FailureDetail;
import com.google.errorprone.annotations.FormatMethod;
import com.google.errorprone.annotations.FormatString;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.logging.Level;
import javax.annotation.Nullable;

/**
 * A spawn strategy that speeds up incremental builds while not slowing down full builds.
 *
 * <p>This strategy tries to run spawn actions on the local and remote machine at the same time, and
 * picks the spawn action that completes first. This gives the benefits of remote execution on full
 * builds, and local execution on incremental builds.
 *
 * <p>One might ask, why we don't run spawns on the workstation all the time and just "spill over"
 * actions to remote execution when there are no local resources available. This would work, except
 * that the cost of transferring action inputs and outputs from the local machine to and from remote
 * executors over the network is way too high - there is no point in executing an action locally and
 * save 0.5s of time, when it then takes us 5 seconds to upload the results to remote executors for
 * another action that's scheduled to run there.
 */
public class DynamicSpawnStrategy implements SpawnStrategy {

  private static final GoogleLogger logger = GoogleLogger.forEnclosingClass();

  private final ListeningExecutorService executorService;
  private final DynamicExecutionOptions options;
  private final Function<Spawn, ExecutionPolicy> getExecutionPolicy;

  /**
   * Set to true by the first action that completes remotely. Until that happens, all local actions
   * are delayed by the amount given in {@link DynamicExecutionOptions#localExecutionDelay}.
   *
   * <p>This is a rather simple approach to make it possible to score a cache hit on remote
   * execution before even trying to start the action locally. This saves resources that would
   * otherwise be wasted by continuously starting and immediately killing local processes. One
   * possibility for improvement would be to establish a reporting mechanism from strategies back to
   * here, where we delay starting locally until the remote strategy tells us that the action isn't
   * a cache hit.
   */
  private final AtomicBoolean delayLocalExecution = new AtomicBoolean(false);

  private final Function<Spawn, Optional<Spawn>> getExtraSpawnForLocalExecution;

  /** If true, this is the first build since the server started. */
  private final boolean firstBuild;

  /** A callback that allows checking if a given failure can be ignored on one branch. */
  private final IgnoreFailureCheck ignoreFailureCheck;

  private boolean skipBuildWarningShown;

  /** Limit on how many threads we should use for dynamic execution. */
  private final Semaphore threadLimiter;

  /**
   * Constructs a {@code DynamicSpawnStrategy}.
   *
   * @param executorService an {@link ExecutorService} that will be used to run Spawn actions.
   * @param firstBuild True if this is the first build since the server started.
   */
  public DynamicSpawnStrategy(
      ExecutorService executorService,
      DynamicExecutionOptions options,
      Function<Spawn, ExecutionPolicy> getExecutionPolicy,
      Function<Spawn, Optional<Spawn>> getPostProcessingSpawnForLocalExecution,
      boolean firstBuild,
      int numCpus,
      IgnoreFailureCheck ignoreFailureCheck) {
    this.executorService = MoreExecutors.listeningDecorator(executorService);
    this.options = options;
    this.getExecutionPolicy = getExecutionPolicy;
    this.getExtraSpawnForLocalExecution = getPostProcessingSpawnForLocalExecution;
    this.firstBuild = firstBuild;
    this.threadLimiter = new Semaphore(numCpus);
    this.ignoreFailureCheck = ignoreFailureCheck;
  }

  @Override
  public boolean canExec(Spawn spawn, ActionContext.ActionContextRegistry actionContextRegistry) {
    ExecutionPolicy executionPolicy = getExecutionPolicy.apply(spawn);
    DynamicStrategyRegistry dynamicStrategyRegistry =
        actionContextRegistry.getContext(DynamicStrategyRegistry.class);

    return canExecLocal(spawn, executionPolicy, actionContextRegistry, dynamicStrategyRegistry)
        || canExecRemote(spawn, executionPolicy, actionContextRegistry, dynamicStrategyRegistry);
  }

  private boolean canExecLocal(
      Spawn spawn,
      ExecutionPolicy mainSpawnExecutionPolicy,
      ActionContext.ActionContextRegistry actionContextRegistry,
      DynamicStrategyRegistry dynamicStrategyRegistry) {
    if (!canExecLocalSpawn(
        spawn, mainSpawnExecutionPolicy, actionContextRegistry, dynamicStrategyRegistry)) {
      return false;
    }
    // Present if there is a extra local spawn. Unset if not.
    Optional<Boolean> canLocalSpawn =
        getExtraSpawnForLocalExecution
            .apply(spawn)
            .map(
                extraSpawn ->
                    canExecLocalSpawn(
                        extraSpawn,
                        getExecutionPolicy.apply(extraSpawn),
                        actionContextRegistry,
                        dynamicStrategyRegistry));
    return canLocalSpawn.orElse(true);
  }

  private static boolean canExecLocalSpawn(
      Spawn spawn,
      ExecutionPolicy executionPolicy,
      ActionContext.ActionContextRegistry actionContextRegistry,
      DynamicStrategyRegistry dynamicStrategyRegistry) {
    if (!executionPolicy.canRunLocally()) {
      return false;
    }
    List<SandboxedSpawnStrategy> localStrategies =
        dynamicStrategyRegistry.getDynamicSpawnActionContexts(spawn, LOCAL);
    return localStrategies.stream()
        .anyMatch(
            s ->
                (s.canExec(spawn, actionContextRegistry)
                    || s.canExecWithLegacyFallback(spawn, actionContextRegistry)));
  }

  private static boolean canExecRemote(
      Spawn spawn,
      ExecutionPolicy executionPolicy,
      ActionContext.ActionContextRegistry actionContextRegistry,
      DynamicStrategyRegistry dynamicStrategyRegistry) {
    if (!executionPolicy.canRunRemotely()) {
      return false;
    }
    List<SandboxedSpawnStrategy> remoteStrategies =
        dynamicStrategyRegistry.getDynamicSpawnActionContexts(
            spawn, DynamicStrategyRegistry.DynamicMode.REMOTE);
    return remoteStrategies.stream().anyMatch(s -> s.canExec(spawn, actionContextRegistry));
  }

  @Override
  public ImmutableList<SpawnResult> exec(
      final Spawn spawn, final ActionExecutionContext actionExecutionContext)
      throws ExecException, InterruptedException {
    DynamicSpawnStrategy.verifyAvailabilityInfo(options, spawn);
    ImmutableList<SpawnResult> nonDynamicResults =
        maybeExecuteNonDynamically(spawn, actionExecutionContext);
    if (nonDynamicResults != null) {
      return nonDynamicResults;
    }

    // True if we got the threads we need for actual dynamic execution.
    boolean gotThreads = false;
    try {
      if (threadLimiter.tryAcquire()) {
        gotThreads = true;
      } else {
        // If there are no threads available for dynamic execution because we're limited
        // to the number of CPUs, we can just execute remotely.
        return RemoteBranch.runRemotely(spawn, actionExecutionContext, null, delayLocalExecution);
      }

      // Extra logging to debug b/194373457
      logger.atInfo().atMostEvery(1, TimeUnit.SECONDS).log(
          "Spawn %s dynamically executed both ways", spawn.getResourceOwner().describe());
      debugLog("Dynamic execution of %s beginning%n", spawn.getResourceOwner().prettyPrint());
      // else both can exec. Fallthrough to below.

      // Semaphores to track termination of each branch. These are necessary to wait for the branch
      // to finish its own cleanup (e.g. terminating subprocesses) once it has been cancelled.

      AtomicReference<DynamicMode> strategyThatCancelled = new AtomicReference<>(null);

      LocalBranch localBranch =
          new LocalBranch(
              actionExecutionContext,
              spawn,
              strategyThatCancelled,
              options,
              ignoreFailureCheck,
              getExtraSpawnForLocalExecution,
              delayLocalExecution);
      RemoteBranch remoteBranch =
          new RemoteBranch(
              actionExecutionContext,
              spawn,
              strategyThatCancelled,
              options,
              ignoreFailureCheck,
              delayLocalExecution);

      SettableFuture<ImmutableList<SpawnResult>> localFuture =
          localBranch.prepareFuture(remoteBranch);
      SettableFuture<ImmutableList<SpawnResult>> remoteFuture =
          remoteBranch.prepareFuture(localBranch);
      localFuture.setFuture(executorService.submit(localBranch));
      remoteFuture.setFuture(executorService.submit(remoteBranch));

      try {
        return waitBranches(localBranch, remoteBranch, spawn, options, actionExecutionContext);
      } finally {
        checkState(localBranch.isDone());
        checkState(remoteBranch.isDone());
        logger.atInfo().atMostEvery(1, TimeUnit.SECONDS).log(
            "Dynamic execution of %s ended with local %s, remote %s%n",
            spawn.getResourceOwner().prettyPrint(),
            localBranch.isCancelled() ? "cancelled" : "done",
            remoteBranch.isCancelled() ? "cancelled" : "done");
        debugLog(
            "Dynamic execution of %s ended with local %s, remote %s%n",
            spawn.getResourceOwner().prettyPrint(),
            localBranch.isCancelled() ? "cancelled" : "done",
            remoteBranch.isCancelled() ? "cancelled" : "done");
      }
    } finally {
      if (gotThreads) {
        threadLimiter.release();
      }
    }
  }

  /**
   * Checks if the given spawn has the right execution requirements to indicate whether it can
   * succeed when running remotely and/or locally depending on the Xcode versions it needs.
   *
   * @param options the dynamic execution options that configure this check
   * @param spawn the spawn to validate
   * @throws ExecException if the spawn does not contain the expected execution requirements
   */
  static void verifyAvailabilityInfo(DynamicExecutionOptions options, Spawn spawn)
      throws ExecException {
    if (options.requireAvailabilityInfo
        && !options.availabilityInfoExempt.contains(spawn.getMnemonic())) {
      if (spawn.getExecutionInfo().containsKey(ExecutionRequirements.REQUIRES_DARWIN)
          && !spawn.getExecutionInfo().containsKey(ExecutionRequirements.REQUIREMENTS_SET)) {
        String message =
            String.format(
                "The following spawn was missing Xcode-related execution requirements. Please"
                    + " let the Bazel team know if you encounter this issue. You can work around"
                    + " this error by passing --experimental_require_availability_info=false --"
                    + " at your own risk! This may cause some actions to be executed on the"
                    + " wrong platform, which can result in build failures.\n"
                    + "Failing spawn: mnemonic = %s\n"
                    + "tool files = %s\n"
                    + "execution platform = %s\n"
                    + "execution info = %s\n",
                spawn.getMnemonic(),
                spawn.getToolFiles(),
                spawn.getExecutionPlatform(),
                spawn.getExecutionInfo());

        FailureDetail detail =
            FailureDetail.newBuilder()
                .setMessage(message)
                .setDynamicExecution(
                    DynamicExecution.newBuilder().setCode(Code.XCODE_RELATED_PREREQ_UNMET))
                .build();
        throw new EnvironmentalExecException(detail);
      }
    }
  }

  /**
   * Checks if this action should be executed dynamically, and if not executes it locally or
   * remotely as applicable, or throws an exception if it cannot be executed at all.
   *
   * @param spawn Spawn in the process of being executed.
   * @param actionExecutionContext Execution context
   * @return Results from execution if the action was executed (possibly empty) or null if this
   *     action can be executed dynamically.
   * @throws ExecException If we tried to execute and executed failed.
   * @throws InterruptedException If we tried to execute and got interrupted.
   */
  @Nullable
  private ImmutableList<SpawnResult> maybeExecuteNonDynamically(
      Spawn spawn, ActionExecutionContext actionExecutionContext)
      throws ExecException, InterruptedException {
    ExecutionPolicy executionPolicy = getExecutionPolicy.apply(spawn);

    DynamicStrategyRegistry dynamicStrategyRegistry =
        actionExecutionContext.getContext(DynamicStrategyRegistry.class);
    boolean localCanExec =
        canExecLocal(spawn, executionPolicy, actionExecutionContext, dynamicStrategyRegistry);

    boolean remoteCanExec =
        canExecRemote(spawn, executionPolicy, actionExecutionContext, dynamicStrategyRegistry);

    if (!localCanExec && !remoteCanExec) {
      FailureDetail failure =
          FailureDetail.newBuilder()
              .setMessage(
                  getNoCanExecFailureMessage(
                      spawn, executionPolicy.canRunLocally(), executionPolicy.canRunRemotely()))
              .setDynamicExecution(
                  DynamicExecution.newBuilder().setCode(Code.NO_USABLE_STRATEGY_FOUND).build())
              .setSpawn(
                  FailureDetails.Spawn.newBuilder()
                      .setCode(FailureDetails.Spawn.Code.NO_USABLE_STRATEGY_FOUND)
                      .build())
              .build();
      debugLog(
          "Dynamic execution of %s can be done neither locally nor remotely%n",
          spawn.getResourceOwner().prettyPrint());
      throw new UserExecException(failure);
    } else if (!localCanExec && remoteCanExec) {
      // Extra logging to debug b/194373457
      logger.atInfo().atMostEvery(1, TimeUnit.SECONDS).log(
          "Dynamic execution of %s can only be done remotely: Local execution policy %s it, "
              + "local strategies are %s.%n",
          spawn.getResourceOwner().prettyPrint(),
          executionPolicy.canRunLocally() ? "allows" : "forbids",
          dynamicStrategyRegistry.getDynamicSpawnActionContexts(spawn, DynamicMode.LOCAL));
      debugLog(
          "Dynamic execution of %s can only be done remotely: Local execution policy %s it, "
              + "local strategies are %s.%n",
          spawn.getResourceOwner().prettyPrint(),
          executionPolicy.canRunLocally() ? "allows" : "forbids",
          dynamicStrategyRegistry.getDynamicSpawnActionContexts(spawn, DynamicMode.LOCAL));
      return RemoteBranch.runRemotely(spawn, actionExecutionContext, null, delayLocalExecution);
    } else if (localCanExec && !remoteCanExec) {
      // Extra logging to debug b/194373457
      logger.atInfo().atMostEvery(1, TimeUnit.SECONDS).log(
          "Dynamic execution of %s can only be done locally: Remote execution policy %s it, "
              + "remote strategies are %s.%n",
          spawn.getResourceOwner().prettyPrint(),
          executionPolicy.canRunRemotely() ? "allows" : "forbids",
          dynamicStrategyRegistry.getDynamicSpawnActionContexts(spawn, DynamicMode.REMOTE));
      debugLog(
          "Dynamic execution of %s can only be done locally: Remote execution policy %s it, "
              + "remote strategies are %s.%n",
          spawn.getResourceOwner().prettyPrint(),
          executionPolicy.canRunRemotely() ? "allows" : "forbids",
          dynamicStrategyRegistry.getDynamicSpawnActionContexts(spawn, DynamicMode.REMOTE));
      return LocalBranch.runLocally(
          spawn, actionExecutionContext, null, getExtraSpawnForLocalExecution);
    } else if (options.skipFirstBuild && firstBuild) {
      if (!skipBuildWarningShown) {
        skipBuildWarningShown = true;
        actionExecutionContext
            .getEventHandler()
            .handle(
                Event.info(
                    "Disabling dynamic execution until we have seen a successful build, see"
                        + " --experimental_dynamic_skip_first_build."));
      }
      return RemoteBranch.runRemotely(spawn, actionExecutionContext, null, delayLocalExecution);
    }
    return null;
  }

  /**
   * Returns an error string for being unable to execute locally and/or remotely the given execution
   * state.
   *
   * <p>Usage note, this method is only to be called after an impossible condition is already
   * detected by the caller, as all this does is give an error string to put in the exception.
   *
   * @param spawn The action that needs to be executed.
   * @param localAllowedBySpawnExecutionPolicy whether the execution policy for this spawn allows
   *     trying local execution.
   * @param remoteAllowedBySpawnExecutionPolicy whether the execution policy for this spawn allows
   *     trying remote execution.
   */
  private static String getNoCanExecFailureMessage(
      Spawn spawn,
      boolean localAllowedBySpawnExecutionPolicy,
      boolean remoteAllowedBySpawnExecutionPolicy) {
    // TODO(b/188387840): Can't use Spawn.toString() here because tests report FakeOwner instances
    // as the resource owner, and those cause toStrings to throw if no primary output.
    // TODO(b/188402092): Even if the above is fixed, we still don't want to use Spawn.toString()
    // until the mnemonic is included in the output unconditionally. Too useful for the error
    // message.
    if (!localAllowedBySpawnExecutionPolicy && !remoteAllowedBySpawnExecutionPolicy) {
      return "Neither local nor remote execution allowed for action " + spawn.getMnemonic();
    } else if (!remoteAllowedBySpawnExecutionPolicy) {
      return "No usable dynamic_local_strategy found (and remote execution disabled) for action "
          + spawn.getMnemonic();
    } else if (!localAllowedBySpawnExecutionPolicy) {
      return "No usable dynamic_remote_strategy found (and local execution disabled) for action "
          + spawn.getMnemonic();
    } else {
      return "No usable dynamic_local_strategy or dynamic_remote_strategy found for action "
          + spawn.getMnemonic();
    }
  }

  /**
   * Waits for the two branches of a spawn's execution to complete.
   *
   * <p>This guarantees that the two branches are stopped both on successful termination and on an
   * exception.
   *
   * @param localBranch the future running the local side of the spawn. This future must cancel
   *     {@code remoteBranch} at some point during its successful execution to guarantee
   *     termination. If we encounter an execution error, or if we are interrupted, then we handle
   *     such cancellation here.
   * @param remoteBranch the future running the remote side of the spawn. Same restrictions apply as
   *     in {@code localBranch}, but in the symmetric direction.
   * @param options the options relevant for dynamic execution
   * @param context execution context object
   * @return the result of the branch that terminates first
   * @throws ExecException the execution error of the spawn that terminated first
   * @throws InterruptedException if we get interrupted while waiting for completion
   */
  @VisibleForTesting
  static ImmutableList<SpawnResult> waitBranches(
      LocalBranch localBranch,
      RemoteBranch remoteBranch,
      Spawn spawn,
      DynamicExecutionOptions options,
      ActionExecutionContext context)
      throws ExecException, InterruptedException {
    ImmutableList<SpawnResult> localResult;
    try {
      localResult = waitBranch(localBranch, options, LOCAL, context);
    } catch (ExecException | InterruptedException | RuntimeException e) {
      if (options.debugSpawnScheduler) {
        context
            .getEventHandler()
            .handle(
                Event.info(
                    String.format(
                        "Cancelling remote branch of %s after local exception %s",
                        spawn.getResourceOwner().prettyPrint(), e.getMessage())));
      }
      remoteBranch.cancel();
      throw e;
    }

    ImmutableList<SpawnResult> remoteResult = waitBranch(remoteBranch, options, REMOTE, context);

    if (remoteResult != null && localResult != null) {
      throw new AssertionError(
          String.format(
              "Neither branch of %s cancelled the other one. Local was %s and remote was %s.",
              spawn.getResourceOwner().getPrimaryOutput().prettyPrint(),
              localBranch.branchState(),
              remoteBranch.branchState()));
    } else if (localResult != null) {
      return localResult;
    } else if (remoteResult != null) {
      return remoteResult;
    } else {
      // TODO(b/173153395): Sometimes gets thrown for currently unknown reasons.
      // (sometimes happens in relation to the whole dynamic execution being cancelled)
      throw new AssertionError(
          String.format(
              "Neither branch of %s completed. Local was %s and remote was %s.",
              spawn.getResourceOwner().getPrimaryOutput().prettyPrint(),
              localBranch.branchState(),
              remoteBranch.branchState()));
    }
  }

  /**
   * Waits for a branch (a spawn execution) to complete.
   *
   * @param branch the future running the spawn
   * @param options the options relevant for dynamic execution
   * @param context execution context object
   * @return the spawn result if the execution terminated successfully, or null if the branch was
   *     cancelled
   * @throws ExecException the execution error of the spawn if it failed
   * @throws InterruptedException if we get interrupted while waiting for completion
   */
  @Nullable
  private static ImmutableList<SpawnResult> waitBranch(
      Branch branch,
      DynamicExecutionOptions options,
      DynamicMode mode,
      ActionExecutionContext context)
      throws ExecException, InterruptedException {
    try {
      ImmutableList<SpawnResult> spawnResults = branch.getResults();
      if (spawnResults == null && options.debugSpawnScheduler) {
        context
            .getEventHandler()
            .handle(
                Event.info(
                    String.format(
                        "Null results from  %s branch of %s",
                        mode,
                        branch.getSpawn().getResourceOwner().getPrimaryOutput().prettyPrint())));
      }
      return spawnResults;
    } catch (CancellationException e) {
      if (options.debugSpawnScheduler) {
        context
            .getEventHandler()
            .handle(
                Event.info(
                    String.format(
                        "CancellationException of %s branch of %s, returning null",
                        mode,
                        branch.getSpawn().getResourceOwner().getPrimaryOutput().prettyPrint())));
      }
      return null;
    } catch (ExecutionException e) {
      Throwable cause = e.getCause();
      if (cause instanceof ExecException) {
        throw (ExecException) cause;
      } else if (cause instanceof InterruptedException) {
        // If the branch was interrupted, it might be due to a user interrupt or due to our request
        // for cancellation. Assume the latter here because if this was actually a user interrupt,
        // our own get() would have been interrupted as well. It makes no sense to propagate the
        // interrupt status across threads.
        context
            .getEventHandler()
            .handle(
                Event.info(
                    String.format(
                        "Caught InterruptedException from ExecException for %s branch of %s, which"
                            + " may cause a crash.",
                        mode,
                        branch.getSpawn().getResourceOwner().getPrimaryOutput().prettyPrint())));
        return null;
      } else {
        // Even though we cannot enforce this in the future's signature (but we do in Branch#call),
        // we only expect the exception types we validated above. Still, unchecked exceptions could
        // propagate, so just let them bubble up.
        Throwables.throwIfUnchecked(cause);
        throw new AssertionError(
            String.format(
                "Unexpected exception type %s from %s strategy.exec()",
                cause.getClass().getName(), mode));
      }
    } catch (InterruptedException e) {
      branch.cancel();
      throw e;
    }
  }

  /**
   * Cancels and waits for a branch (a spawn execution) to terminate.
   *
   * <p>This is intended to be used as the body of the {@link
   * SandboxedSpawnStrategy.StopConcurrentSpawns} lambda passed to the spawn runners. Each strategy
   * may call this at most once.
   *
   * @param otherBranch The other branch, the one that should be cancelled.
   * @param cancellingBranch The branch that is performing the cancellation.
   * @param cancellingStrategy identifier of the strategy that is performing the cancellation. Used
   *     to prevent cross-cancellations and to check that the same strategy doesn't issue the
   *     cancellation twice.
   * @param strategyThatCancelled name of the first strategy that executed this method, or a null
   *     reference if this is the first time this method is called. If not null, we expect the value
   *     referenced by this to be different than {@code cancellingStrategy}, or else we have a bug.
   * @param options The options for dynamic execution.
   * @param context The context of this action execution.
   * @throws InterruptedException if we get interrupted for any reason trying to cancel the future
   * @throws DynamicInterruptedException if we lost a race against another strategy trying to cancel
   *     us
   */
  static void stopBranch(
      Branch otherBranch,
      Branch cancellingBranch,
      DynamicMode cancellingStrategy,
      AtomicReference<DynamicMode> strategyThatCancelled,
      DynamicExecutionOptions options,
      ActionExecutionContext context)
      throws InterruptedException {
    if (cancellingBranch.isCancelled()) {
      // TODO(b/173020239): Determine why stopBranch() can be called when cancellingBranch is
      // cancelled.
      throw new DynamicInterruptedException(
          String.format(
              "Execution of %s strategy stopped because it was cancelled but not interrupted",
              cancellingStrategy));
    }
    // This multi-step, unlocked access to "strategyThatCancelled" is valid because, for a given
    // value of "cancellingStrategy", we do not expect concurrent calls to this method. (If there
    // are, we are in big trouble.)
    DynamicMode current = strategyThatCancelled.get();
    if (cancellingStrategy.equals(current)) {
      throw new AssertionError("stopBranch called more than once by " + cancellingStrategy);
    } else {
      // Protect against the two branches from cancelling each other. The first branch to set the
      // reference to its own identifier wins and is allowed to issue the cancellation; the other
      // branch just has to give up execution.
      if (strategyThatCancelled.compareAndSet(null, cancellingStrategy)) {
        if (options.debugSpawnScheduler) {
          context
              .getEventHandler()
              .handle(
                  Event.info(
                      String.format(
                          "%s action finished %sly and was %s",
                          cancellingBranch.getSpawn().getMnemonic(),
                          strategyThatCancelled.get(),
                          cancellingBranch.isCancelled() ? "cancelled" : "not cancelled")));
        }

        if (!otherBranch.cancel()) {
          // This can happen if the other branch is local under local_lockfree and has returned
          // its result but not yet cancelled this branch, or if the other branch was already
          // cancelled for other reasons. In the latter case, we are good to continue.
          if (!otherBranch.isCancelled()) {
            throw new DynamicInterruptedException(
                String.format(
                    "Execution of %s strategy stopped because %s strategy could not be cancelled",
                    cancellingStrategy, cancellingStrategy.other()));
          }
        }
        otherBranch.getDoneSemaphore().acquire();
      } else {
        throw new DynamicInterruptedException(
            String.format(
                "Execution of %s strategy stopped because %s strategy finished first",
                cancellingStrategy, strategyThatCancelled.get()));
      }
    }
  }

  @FormatMethod
  private void stepLog(
      Level level, @Nullable Throwable cause, @FormatString String fmt, Object... args) {
    logger.at(level).withCause(cause).logVarargs(fmt, args);
  }

  @FormatMethod
  private void debugLog(String fmt, Object... args) {
    if (options.debugSpawnScheduler) {
      stepLog(Level.FINE, null, fmt, args);
    }
  }

  @Override
  public void usedContext(ActionContext.ActionContextRegistry actionContextRegistry) {
    actionContextRegistry
        .getContext(DynamicStrategyRegistry.class)
        .notifyUsedDynamic(actionContextRegistry);
  }

  @Override
  public String toString() {
    return "dynamic";
  }
}
