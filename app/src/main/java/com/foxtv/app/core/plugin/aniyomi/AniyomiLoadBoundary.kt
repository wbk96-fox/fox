package com.foxtv.app.core.plugin.aniyomi

import eu.kanade.tachiyomi.animesource.AnimeSourceFactory
import com.foxtv.app.core.plugin.aniyomi.host.AniyomiHostRuntime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Modifier
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** Owns every in-process Aniyomi loader and factory reference. Raw runtime objects never escape. */
@Singleton
internal class AniyomiLoadBoundary @Inject constructor(
    private val inspector: AniyomiStaticApkInspector,
    private val artifacts: AniyomiArtifactStore,
    private val state: AniyomiLoadBoundaryStateStore,
    private val legacyDexCache: AniyomiCodeCacheStore,
    private val classLoaders: AniyomiClassLoaderFactory,
    private val hostRuntime: AniyomiHostRuntime,
) : AniyomiSelectionChangeBarrier {
    private val mutex = Mutex()
    private var active: ActiveSession? = null

    suspend fun enable(): AniyomiLoadBoundaryOutcome = mutex.withLock {
        boundaryOutcome {
            normalizeStaleRecordIfNecessary()
            val current = state.read() ?: AniyomiLoadBoundaryRecord.disabled()
            if (current.desiredEnabled) {
                return@boundaryOutcome AniyomiLoadBoundaryOutcome.Enabled(alreadyEnabled = true)
            }
            state.write(AniyomiLoadBoundaryRecord.enabledIdle())
            AniyomiLoadBoundaryOutcome.Enabled(alreadyEnabled = false)
        }
    }

    suspend fun activate(selected: AniyomiSelectedGeneration): AniyomiLoadBoundaryOutcome =
        mutex.withLock {
            boundaryOutcome { activateLocked(selected) }
        }

    suspend fun release(): AniyomiLoadBoundaryOutcome = mutex.withLock {
        boundaryOutcome {
            normalizeStaleRecordIfNecessary()
            val current = state.read() ?: AniyomiLoadBoundaryRecord.disabled()
            if (!current.desiredEnabled) return@boundaryOutcome AniyomiLoadBoundaryOutcome.Disabled
            releaseLocked(
                desiredEnabled = true,
                lastKnownGood = active?.generation ?: current.lastKnownGood,
                failedGeneration = current.failedGeneration,
            )
            AniyomiLoadBoundaryOutcome.Released(enabled = true)
        }
    }

    suspend fun disable(): AniyomiLoadBoundaryOutcome = mutex.withLock {
        boundaryOutcome {
            normalizeStaleRecordIfNecessary()
            releaseLocked(
                desiredEnabled = false,
                lastKnownGood = null,
                failedGeneration = null,
            )
            AniyomiLoadBoundaryOutcome.Disabled
        }
    }

    suspend fun recoverAfterProcessDeath(): AniyomiLoadBoundaryOutcome = mutex.withLock {
        boundaryOutcome { recoverAfterProcessDeathLocked() }
    }

    /**
     * Consumes one exact failed/LKG authorization while retaining the M3 mutex through the M2
     * pointer swap callback and activation. The caller must already own M2, establishing the only
     * nested lock order: M2 then M3.
     */
    internal suspend fun consumeRollbackAndActivate(
        request: AniyomiActivationRollbackRequest,
        selectRollback: suspend () -> AniyomiSelectedGeneration,
    ): AniyomiLoadBoundaryOutcome = mutex.withLock {
        boundaryOutcome {
            normalizeStaleRecordIfNecessary()
            val record = state.read() ?: AniyomiLoadBoundaryRecord.disabled()
            if (active != null ||
                record.phase != AniyomiLoadBoundaryPhase.ENABLED_IDLE ||
                !record.desiredEnabled ||
                record.failedGeneration != request.failedGeneration ||
                record.lastKnownGood != request.lastKnownGood
            ) {
                throw loadFailure(
                    AniyomiLoadBoundaryFailureCode.ROLLBACK_REJECTED,
                    "Aniyomi rollback authorization is stale, consumed, or disabled",
                )
            }
            val selected = selectRollback()
            if (selected.reference != request.lastKnownGood) {
                throw loadFailure(
                    AniyomiLoadBoundaryFailureCode.ROLLBACK_REJECTED,
                    "M2 produced a different Aniyomi rollback target than M3 authorized",
                )
            }
            activateLocked(selected)
        }
    }

    suspend fun activeMetadata(): AniyomiLoadBoundaryOutcome.Activated? = mutex.withLock {
        active?.metadata(reusedExisting = true)
    }

    override suspend fun beforeSelectionChange(
        currentSelection: AniyomiLoadGenerationRef?,
        nextSelection: AniyomiLoadGenerationRef?,
    ) {
        if (currentSelection == nextSelection) return
        mutex.withLock {
            normalizeStaleRecordIfNecessary()
            val current = state.read() ?: AniyomiLoadBoundaryRecord.disabled()
            val owned = active
            if (owned != null && owned.generation != currentSelection) {
                throw AniyomiLoadBoundaryException(
                    AniyomiLoadBoundaryFailure(
                        AniyomiLoadBoundaryFailureCode.STALE_GENERATION,
                        "Active Aniyomi owner does not match the complete M2 selection identity",
                    ),
                )
            }
            releaseLocked(
                desiredEnabled = current.desiredEnabled,
                lastKnownGood = owned?.generation ?: current.lastKnownGood,
                failedGeneration = current.failedGeneration,
            )
        }
    }

    private suspend fun activateLocked(
        selected: AniyomiSelectedGeneration,
    ): AniyomiLoadBoundaryOutcome {
        normalizeStaleRecordIfNecessary()
        val current = state.read() ?: AniyomiLoadBoundaryRecord.disabled()
        if (!current.desiredEnabled) {
            throw loadFailure(
                AniyomiLoadBoundaryFailureCode.DISABLED,
                "Aniyomi loading is disabled",
            )
        }
        active?.let { owned ->
            if (owned.generation == selected.reference) {
                return owned.metadata(reusedExisting = true)
            }
            throw loadFailure(
                AniyomiLoadBoundaryFailureCode.DUPLICATE_OWNER,
                "A different Aniyomi generation already owns the load boundary",
            )
        }
        if (current.phase != AniyomiLoadBoundaryPhase.ENABLED_IDLE) {
            throw loadFailure(
                AniyomiLoadBoundaryFailureCode.RECOVERY_FAILED,
                "Aniyomi load boundary is not idle after recovery",
            )
        }
        if (!hostRuntime.isInitialized()) {
            throw loadFailure(
                AniyomiLoadBoundaryFailureCode.HOST_NOT_READY,
                "Aniyomi parent host runtime is not initialized",
            )
        }

        val source = resolveExactSource(selected)
        val ownership = AniyomiClassOwnershipPolicy.from(selected)
        val operationId = UUID.randomUUID().toString()
        state.write(
            AniyomiLoadBoundaryRecord(
                phase = AniyomiLoadBoundaryPhase.ACTIVATING,
                desiredEnabled = true,
                operationId = operationId,
                target = selected.reference,
                lastKnownGood = current.lastKnownGood,
                failedGeneration = null,
            ),
        )

        var handle: AniyomiOwnedClassLoaderHandle? = null
        var legacyWorkspacePrepared = false
        try {
            val legacyOptimizedDirectory = legacyDexCache.prepare(selected.identity.generationId)
            legacyWorkspacePrepared = true
            val loaded = runInterruptible(Dispatchers.IO) {
                val opened = classLoaders.open(source, legacyOptimizedDirectory, ownership)
                handle = opened
                constructFactory(opened, ownership)
            }
            currentCoroutineContext().ensureActive()
            val exact = inspectExactSource(source, selected)
            if (exact.identity != selected.identity) {
                throw loadFailure(
                    AniyomiLoadBoundaryFailureCode.STALE_GENERATION,
                    "Aniyomi generation changed between M2 handoff and load publication",
                )
            }
            val sessionId = UUID.randomUUID().toString()
            val activeRecord = AniyomiLoadBoundaryRecord(
                phase = AniyomiLoadBoundaryPhase.ACTIVE,
                desiredEnabled = true,
                sessionId = sessionId,
                target = selected.reference,
                lastKnownGood = selected.reference,
            )
            state.write(activeRecord)
            val session = ActiveSession(
                sessionId = sessionId,
                generation = selected.reference,
                loader = requireNotNull(handle),
                factory = loaded,
            )
            active = session
            return session.metadata(reusedExisting = false)
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) {
                cleanupFailedActivation(
                    selected = selected.reference,
                    lastKnownGood = current.lastKnownGood,
                    handle = handle,
                    legacyWorkspacePrepared = legacyWorkspacePrepared,
                    original = cancelled,
                )
            }
            throw cancelled
        } catch (error: AniyomiLoadBoundaryException) {
            withContext(NonCancellable) {
                cleanupFailedActivation(
                    selected = selected.reference,
                    lastKnownGood = current.lastKnownGood,
                    handle = handle,
                    legacyWorkspacePrepared = legacyWorkspacePrepared,
                    original = error,
                )
            }
            throw error
        } catch (error: Exception) {
            val wrapped = loadFailure(
                AniyomiLoadBoundaryFailureCode.INITIALIZATION_FAILED,
                "Aniyomi factory could not be loaded",
                error,
            )
            withContext(NonCancellable) {
                cleanupFailedActivation(
                    selected = selected.reference,
                    lastKnownGood = current.lastKnownGood,
                    handle = handle,
                    legacyWorkspacePrepared = legacyWorkspacePrepared,
                    original = wrapped,
                )
            }
            throw wrapped
        }
    }

    private fun constructFactory(
        handle: AniyomiOwnedClassLoaderHandle,
        ownership: AniyomiClassOwnershipPolicy,
    ): AnimeSourceFactory {
        val thread = Thread.currentThread()
        val previousContextLoader = thread.contextClassLoader
        return try {
            thread.contextClassLoader = handle.classLoader
            constructFactoryWithOwnedContext(handle, ownership)
        } finally {
            thread.contextClassLoader = previousContextLoader
        }
    }

    private fun constructFactoryWithOwnedContext(
        handle: AniyomiOwnedClassLoaderHandle,
        ownership: AniyomiClassOwnershipPolicy,
    ): AnimeSourceFactory {
        val factoryClass = try {
            handle.loadChildClass(ownership.factoryClassName)
        } catch (error: ClassNotFoundException) {
            throw loadFailure(
                AniyomiLoadBoundaryFailureCode.CLASS_NOT_FOUND,
                "Selected Aniyomi factory class is not loadable",
                error,
            )
        } catch (error: LinkageError) {
            throw loadFailure(
                AniyomiLoadBoundaryFailureCode.INITIALIZATION_FAILED,
                "Selected Aniyomi factory failed class linking or initialization",
                error,
            )
        }
        if (factoryClass.classLoader !== handle.classLoader) {
            throw loadFailure(
                AniyomiLoadBoundaryFailureCode.CLASS_OWNERSHIP_VIOLATION,
                "Aniyomi factory was not defined by the owned child loader",
            )
        }
        if (!AnimeSourceFactory::class.java.isAssignableFrom(factoryClass) ||
            !Modifier.isPublic(factoryClass.modifiers) ||
            Modifier.isAbstract(factoryClass.modifiers) ||
            factoryClass.isInterface
        ) {
            throw loadFailure(
                AniyomiLoadBoundaryFailureCode.TYPE_MISMATCH,
                "Loaded Aniyomi factory does not implement the parent-owned host contract",
            )
        }
        val constructor = try {
            factoryClass.getConstructor()
        } catch (error: NoSuchMethodException) {
            throw loadFailure(
                AniyomiLoadBoundaryFailureCode.CONSTRUCTOR_MISSING,
                "Aniyomi factory has no public no-argument constructor",
                error,
            )
        }
        return try {
            constructor.newInstance() as AnimeSourceFactory
        } catch (error: InvocationTargetException) {
            throw loadFailure(
                AniyomiLoadBoundaryFailureCode.CONSTRUCTOR_FAILED,
                "Aniyomi factory constructor failed",
                error.targetException ?: error,
            )
        } catch (error: ExceptionInInitializerError) {
            throw loadFailure(
                AniyomiLoadBoundaryFailureCode.INITIALIZATION_FAILED,
                "Aniyomi factory static initialization failed",
                error.exception ?: error,
            )
        } catch (error: LinkageError) {
            throw loadFailure(
                AniyomiLoadBoundaryFailureCode.INITIALIZATION_FAILED,
                "Aniyomi factory linkage failed during construction",
                error,
            )
        } catch (error: ReflectiveOperationException) {
            throw loadFailure(
                AniyomiLoadBoundaryFailureCode.CONSTRUCTOR_FAILED,
                "Aniyomi factory could not be constructed",
                error,
            )
        }
    }

    private suspend fun inspectExactSource(
        source: File,
        selected: AniyomiSelectedGeneration,
    ): ExactInspection {
        val inspected = runInterruptible(Dispatchers.IO) {
            inspector.inspect(source, selected.policy.policy)
        }
        val accepted = when (inspected) {
            is AniyomiApkInspectionResult.Accepted -> inspected.artifact
            is AniyomiApkInspectionResult.Rejected -> throw loadFailure(
                AniyomiLoadBoundaryFailureCode.POISONED_GENERATION,
                "Selected Aniyomi generation failed full pre-publication inspection: " +
                    inspected.failures.joinToString { it.code.name },
            )
        }
        val identity = AniyomiArtifactIdentity.fromInspection(
            accepted,
            AniyomiStoredArtifactKind.GENERATION,
            transactionId = null,
            selected.policy,
        )
        return ExactInspection(identity, accepted)
    }

    private suspend fun resolveExactSource(selected: AniyomiSelectedGeneration): File {
        val resolved = try {
            artifacts.resolve(selected.identity)
        } catch (error: AniyomiLifecycleException) {
            throw loadFailure(
                AniyomiLoadBoundaryFailureCode.UNSAFE_CODE_SOURCE,
                "M2 selected generation cannot be resolved safely",
                error,
            )
        }
        val expected = selected.file.absoluteFile
        val actual = resolved.absoluteFile
        val expectedCanonical = runCatching { expected.canonicalFile }.getOrNull()
        val actualCanonical = runCatching { actual.canonicalFile }.getOrNull()
        if (expectedCanonical == null || actualCanonical == null ||
            expectedCanonical.path != expected.path || actualCanonical.path != actual.path ||
            expectedCanonical.path != actualCanonical.path || !actualCanonical.isFile ||
            actualCanonical.name != ANIYOMI_APK_FILE_NAME ||
            actualCanonical.parentFile?.name != selected.identity.generationId
        ) {
            throw loadFailure(
                AniyomiLoadBoundaryFailureCode.UNSAFE_CODE_SOURCE,
                "M3 code source is not the exact canonical M2 generation",
            )
        }
        return actualCanonical
    }

    private suspend fun cleanupFailedActivation(
        selected: AniyomiLoadGenerationRef,
        lastKnownGood: AniyomiLoadGenerationRef?,
        handle: AniyomiOwnedClassLoaderHandle?,
        legacyWorkspacePrepared: Boolean,
        original: Throwable,
    ) {
        var cleanupFailure: Throwable? = null
        try {
            state.write(
                AniyomiLoadBoundaryRecord(
                    phase = AniyomiLoadBoundaryPhase.RELEASE_PENDING,
                    desiredEnabled = true,
                    operationId = UUID.randomUUID().toString(),
                    target = selected,
                    lastKnownGood = lastKnownGood,
                    failedGeneration = selected,
                ),
            )
        } catch (error: Throwable) {
            if (error is Error) throw error
            cleanupFailure = error
        }
        active = null
        try {
            handle?.close()
        } catch (error: Throwable) {
            if (error is Error) throw error
            cleanupFailure = combineCleanupFailures(cleanupFailure, error)
        }
        if (legacyWorkspacePrepared) {
            try {
                legacyDexCache.cleanup(selected.generationId)
            } catch (error: Throwable) {
                if (error is Error) throw error
                cleanupFailure = combineCleanupFailures(cleanupFailure, error)
            }
        }
        if (cleanupFailure == null) {
            try {
                state.write(
                    AniyomiLoadBoundaryRecord.enabledIdle(
                        lastKnownGood = lastKnownGood,
                        failedGeneration = selected,
                    ),
                )
            } catch (error: Throwable) {
                if (error is Error) throw error
                cleanupFailure = error
            }
        }
        cleanupFailure?.let { original.addSuppressed(it) }
    }

    private suspend fun releaseLocked(
        desiredEnabled: Boolean,
        lastKnownGood: AniyomiLoadGenerationRef?,
        failedGeneration: AniyomiLoadGenerationRef?,
    ) {
        val current = state.read() ?: AniyomiLoadBoundaryRecord.disabled()
        val owned = active
        val target = owned?.generation ?: current.target
        if (owned == null && target == null &&
            current.phase != AniyomiLoadBoundaryPhase.ACTIVATING &&
            current.phase != AniyomiLoadBoundaryPhase.ACTIVE &&
            current.phase != AniyomiLoadBoundaryPhase.RELEASE_PENDING
        ) {
            state.write(
                if (desiredEnabled) {
                    AniyomiLoadBoundaryRecord.enabledIdle(lastKnownGood, failedGeneration)
                } else {
                    AniyomiLoadBoundaryRecord.disabled()
                },
            )
            return
        }

        state.write(
            AniyomiLoadBoundaryRecord(
                phase = AniyomiLoadBoundaryPhase.RELEASE_PENDING,
                desiredEnabled = desiredEnabled,
                operationId = UUID.randomUUID().toString(),
                target = target,
                lastKnownGood = lastKnownGood,
                failedGeneration = failedGeneration,
            ),
        )
        active = null
        var failure: Throwable? = null
        withContext(NonCancellable) {
            try {
                owned?.loader?.close()
            } catch (error: Throwable) {
                if (error is Error) throw error
                failure = combineCleanupFailures(failure, error)
            }
            target?.let {
                try {
                    legacyDexCache.cleanup(it.generationId)
                } catch (error: Throwable) {
                    if (error is Error) throw error
                    failure = combineCleanupFailures(failure, error)
                }
            }
        }
        failure?.let {
            throw loadFailure(
                AniyomiLoadBoundaryFailureCode.RELEASE_FAILED,
                "Aniyomi loader resources could not be released",
                it,
            )
        }
        state.write(
            if (desiredEnabled) {
                AniyomiLoadBoundaryRecord.enabledIdle(lastKnownGood, failedGeneration)
            } else {
                AniyomiLoadBoundaryRecord.disabled()
            },
        )
    }

    private suspend fun normalizeStaleRecordIfNecessary() {
        val record = state.read() ?: return
        if (active != null) return
        if (record.phase == AniyomiLoadBoundaryPhase.ACTIVATING ||
            record.phase == AniyomiLoadBoundaryPhase.ACTIVE ||
            record.phase == AniyomiLoadBoundaryPhase.RELEASE_PENDING
        ) {
            recoverAfterProcessDeathLocked()
        }
    }

    private suspend fun recoverAfterProcessDeathLocked(): AniyomiLoadBoundaryOutcome {
        active?.let { owned ->
            val liveRecord = state.read()
            if (liveRecord?.phase == AniyomiLoadBoundaryPhase.ACTIVE &&
                liveRecord.sessionId == owned.sessionId &&
                liveRecord.target == owned.generation
            ) {
                return owned.metadata(reusedExisting = true)
            }
            val desiredEnabled = liveRecord?.desiredEnabled == true
            releaseLocked(
                desiredEnabled = desiredEnabled,
                lastKnownGood = liveRecord?.lastKnownGood,
                failedGeneration = liveRecord?.failedGeneration,
            )
            legacyDexCache.recover()
            return if (desiredEnabled) {
                AniyomiLoadBoundaryOutcome.Released(enabled = true)
            } else {
                AniyomiLoadBoundaryOutcome.Disabled
            }
        }
        val record = state.read() ?: AniyomiLoadBoundaryRecord.disabled()
        legacyDexCache.recover()
        val normalized = if (record.desiredEnabled) {
            val lastGood = when (record.phase) {
                AniyomiLoadBoundaryPhase.ACTIVE -> record.target ?: record.lastKnownGood
                else -> record.lastKnownGood
            }
            val failed = when (record.phase) {
                AniyomiLoadBoundaryPhase.ACTIVATING -> record.target ?: record.failedGeneration
                else -> record.failedGeneration
            }
            AniyomiLoadBoundaryRecord.enabledIdle(lastGood, failed)
        } else {
            AniyomiLoadBoundaryRecord.disabled()
        }
        state.write(normalized)
        return if (normalized.desiredEnabled) {
            AniyomiLoadBoundaryOutcome.Released(enabled = true)
        } else {
            AniyomiLoadBoundaryOutcome.Disabled
        }
    }

    private suspend fun <T : AniyomiLoadBoundaryOutcome> boundaryOutcome(
        block: suspend () -> T,
    ): AniyomiLoadBoundaryOutcome = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: AniyomiLoadBoundaryException) {
        AniyomiLoadBoundaryOutcome.Failed(error.failure)
    } catch (error: AniyomiLifecycleException) {
        AniyomiLoadBoundaryOutcome.Failed(
            AniyomiLoadBoundaryFailure(
                AniyomiLoadBoundaryFailureCode.M2_LIFECYCLE_REJECTED,
                "M2 rejected the Aniyomi load-boundary operation",
                error,
            ),
        )
    } catch (error: Exception) {
        AniyomiLoadBoundaryOutcome.Failed(
            AniyomiLoadBoundaryFailure(
                AniyomiLoadBoundaryFailureCode.RECOVERY_FAILED,
                "Aniyomi load boundary failed closed",
                error,
            ),
        )
    }

    private data class ActiveSession(
        val sessionId: String,
        val generation: AniyomiLoadGenerationRef,
        val loader: AniyomiOwnedClassLoaderHandle,
val factory: AnimeSourceFactory,
    ) {
        fun metadata(reusedExisting: Boolean): AniyomiLoadBoundaryOutcome.Activated =
            AniyomiLoadBoundaryOutcome.Activated(
                sessionId = sessionId,
                generation = generation,
                reusedExisting = reusedExisting,
            )
    }

    private data class ExactInspection(
        val identity: AniyomiArtifactIdentity,
val artifact: InspectedAniyomiApk,
    )
}

/** Public orchestration surface; it awaits startup recovery and preserves M2 → M3 ordering. */
@Singleton
internal class AniyomiLoadCoordinator @Inject constructor(
    private val lifecycle: AniyomiLifecycleCoordinator,
    private val boundary: AniyomiLoadBoundary,
    private val startup: AniyomiStartupGate,
) {
    suspend fun enable(): AniyomiLoadBoundaryOutcome {
        startupFailure()?.let { return it }
        return boundary.enable()
    }

    suspend fun enableAndActivate(): AniyomiLoadBoundaryOutcome {
        startupFailure()?.let { return it }
        val enabled = boundary.enable()
        if (enabled is AniyomiLoadBoundaryOutcome.Failed) return enabled
        return activateSelectedAfterStartup()
    }

    suspend fun activateSelected(): AniyomiLoadBoundaryOutcome {
        startupFailure()?.let { return it }
        return activateSelectedAfterStartup()
    }

    suspend fun release(): AniyomiLoadBoundaryOutcome {
        startupFailure()?.let { return it }
        return boundary.release()
    }

    suspend fun disable(): AniyomiLoadBoundaryOutcome {
        startupFailure()?.let { return it }
        return boundary.disable()
    }

    suspend fun rollbackAfterFailedActivation(): AniyomiLoadBoundaryOutcome {
        startupFailure()?.let { return it }
        return try {
            lifecycle.rollbackFailedActivationAndActivate(boundary)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: AniyomiLifecycleException) {
            AniyomiLoadBoundaryOutcome.Failed(
                AniyomiLoadBoundaryFailure(
                    AniyomiLoadBoundaryFailureCode.ROLLBACK_REJECTED,
                    "M2 rejected the pinned explicit activation rollback",
                    error,
                ),
            )
        }
    }

    private suspend fun startupFailure(): AniyomiLoadBoundaryOutcome.Failed? =
        startup.awaitReady()?.let(AniyomiLoadBoundaryOutcome::Failed)

    private suspend fun activateSelectedAfterStartup(): AniyomiLoadBoundaryOutcome = try {
        lifecycle.activateSelected(boundary)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: AniyomiLifecycleException) {
        AniyomiLoadBoundaryOutcome.Failed(
            AniyomiLoadBoundaryFailure(
                if (error.failure.code == AniyomiLifecycleFailureCode.NO_ROLLBACK_CANDIDATE) {
                    AniyomiLoadBoundaryFailureCode.NO_SELECTED_GENERATION
                } else {
                    AniyomiLoadBoundaryFailureCode.M2_LIFECYCLE_REJECTED
                },
                "M2 did not provide a trusted selected generation",
                error,
            ),
        )
    }
}

private fun loadFailure(
    code: AniyomiLoadBoundaryFailureCode,
    message: String,
    cause: Throwable? = null,
): AniyomiLoadBoundaryException = AniyomiLoadBoundaryException(
    AniyomiLoadBoundaryFailure(code, message, cause),
)

private fun combineCleanupFailures(current: Throwable?, next: Throwable): Throwable {
    if (current == null) return next
    current.addSuppressed(next)
    return current
}
