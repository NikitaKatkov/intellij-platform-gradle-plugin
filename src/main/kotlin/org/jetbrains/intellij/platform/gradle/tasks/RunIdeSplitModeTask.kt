// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.

package org.jetbrains.intellij.platform.gradle.tasks

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.UntrackedTask
import org.gradle.kotlin.dsl.named
import org.jetbrains.intellij.platform.gradle.Constants.Plugin
import org.jetbrains.intellij.platform.gradle.Constants.Tasks
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Runs the backend and frontend split-mode tasks as two separate Java processes.
 *
 * This is intended for terminal users who want a one-command split-mode launch while keeping
 * [RunIdeTask] monolithic behavior intact.
 */
@UntrackedTask(because = "Should always run")
abstract class RunIdeSplitModeTask : DefaultTask() {

    @get:Internal
    abstract val backendTask: Property<RunIdeTask>

    @get:Internal
    abstract val frontendTask: Property<RunIdeTask>

    @TaskAction
    fun run() {
        val backendTask = backendTask.get()
        val frontendTask = frontendTask.get()

        backendTask.prepareForExecution()
        val backendProcess = startProcess(backendTask.createProcessSpec())
        val shutdownHook = Thread { backendProcess.destroyForcibly() }
        Runtime.getRuntime().addShutdownHook(shutdownHook)

        try {
            frontendTask.prepareForExecution()
            val frontendExitCode = startProcess(frontendTask.createProcessSpec()).waitFor()
            if (frontendExitCode != 0) {
                throw GradleException("Split-mode frontend process exited with code $frontendExitCode.")
            }
        } finally {
            Runtime.getRuntime().removeShutdownHookSafe(shutdownHook)
            backendProcess.destroy()
            backendProcess.waitForExit()
        }
    }

    private fun startProcess(spec: ProcessSpec): ManagedProcess {
        val processBuilder = ProcessBuilder(spec.commandLine)
            .directory(spec.workingDirectory)

        processBuilder.environment().putAll(spec.environment)

        val process = processBuilder.start()
        val stdoutThread = process.inputStream.forwardTo(spec.standardOutput, "stdout")
        val stderrThread = process.errorStream.forwardTo(spec.errorOutput, "stderr")

        return ManagedProcess(process, stdoutThread, stderrThread)
    }

    init {
        group = Plugin.GROUP_NAME
        description = "Runs the IDE backend and JetBrains Client frontend as separate processes in Split Mode."
        notCompatibleWithConfigurationCache("Starts backend and frontend Java processes from task-specific launch state.")
    }

    companion object : Registrable {
        private const val PREPARE_SANDBOX_RUN_IDE_BACKEND = "${Tasks.PREPARE_SANDBOX}_${Tasks.RUN_IDE_BACKEND}"
        private const val PREPARE_SANDBOX_RUN_IDE_FRONTEND = "${Tasks.PREPARE_SANDBOX}_${Tasks.RUN_IDE_FRONTEND}"

        override fun register(project: Project) {
            val backendTaskProvider = project.tasks.named<RunIdeTask>(Tasks.RUN_IDE_BACKEND)
            val frontendTaskProvider = project.tasks.named<RunIdeTask>(Tasks.RUN_IDE_FRONTEND)

            project.registerTask<RunIdeSplitModeTask>(Tasks.RUN_IDE_SPLIT_MODE, configureWithType = false) {
                backendTask.convention(backendTaskProvider)
                frontendTask.convention(frontendTaskProvider)

                dependsOn(project.tasks.named<PrepareSandboxTask>(PREPARE_SANDBOX_RUN_IDE_BACKEND))
                dependsOn(project.tasks.named<PrepareSandboxTask>(PREPARE_SANDBOX_RUN_IDE_FRONTEND))
            }
        }
    }
}

private data class ManagedProcess(
    val process: Process,
    private val stdoutThread: Thread,
    private val stderrThread: Thread,
) {
    val isAlive get() = process.isAlive

    fun destroy() = process.destroy()

    fun destroyForcibly() {
        if (process.isAlive) {
            process.destroyForcibly()
        }
    }

    fun waitFor() = process.waitFor().also { joinPumpThreads() }

    fun waitForExit(timeoutSeconds: Long = 5) {
        if (process.isAlive) {
            process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
        }
        if (process.isAlive) {
            process.destroyForcibly()
        }
        joinPumpThreads()
    }

    private fun joinPumpThreads() {
        stdoutThread.join(TimeUnit.SECONDS.toMillis(1))
        stderrThread.join(TimeUnit.SECONDS.toMillis(1))
    }
}

private fun InputStream.forwardTo(output: OutputStream, label: String) = thread(
    start = true,
    isDaemon = true,
    name = "runIdeSplitMode-$label",
) {
    use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) {
                output.flush()
                break
            }
            output.write(buffer, 0, read)
            output.flush()
        }
    }
}

private fun Runtime.removeShutdownHookSafe(thread: Thread) = runCatching {
    removeShutdownHook(thread)
}
