/*
 * Copyright @ 2018 Atlassian Pty Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package org.jitsi.jibri.service.impl

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.jitsi.jibri.capture.Capturer
import org.jitsi.jibri.capture.ffmpeg.FfmpegCapturer
import org.jitsi.jibri.capture.ffmpeg.executor.impl.FFMPEG_RESTART_ATTEMPTS
import org.jitsi.jibri.config.XmppCredentials
import org.jitsi.jibri.selenium.CallParams
import org.jitsi.jibri.selenium.JibriSelenium
import org.jitsi.jibri.selenium.JibriSeleniumOptions
import org.jitsi.jibri.selenium.RECORDING_URL_OPTIONS
import org.jitsi.jibri.service.JibriService
import org.jitsi.jibri.service.JibriServiceStatus
import org.jitsi.jibri.sink.Sink
import org.jitsi.jibri.sink.impl.FileSink
import org.jitsi.jibri.util.NameableThreadFactory
import org.jitsi.jibri.util.ProcessMonitor
import org.jitsi.jibri.util.WritableDirectory
import org.jitsi.jibri.util.extensions.error
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.logging.Logger

/**
 * Parameters needed for starting a [FileRecordingJibriService]
 */
data class FileRecordingParams(
    /**
     * Which call we'll join
     */
    val callParams: CallParams,
    /**
     * The login information needed to appear invisible in
     * the call
     */
    val callLoginParams: XmppCredentials,
    /**
     * The filesystem path to the script which should be executed when
     *  the recording is finished.
     */
    val finalizeScriptPath: String,
    /**
     * The directory in which recordings should be created
     */
    val recordingDirectory: WritableDirectory
)

/**
 * Set of metadata we'll put alongside the recording file(s)
 */
data class RecordingMetadata(
    @JsonProperty("meeting_url")
    val meetingUrl: String,
    val participants: List<Map<String, Any>>
)

/**
 * [FileRecordingJibriService] is the [JibriService] responsible for joining
 * a web call, capturing its audio and video, and writing that audio and video
 * to a file to be replayed later.
 */
class FileRecordingJibriService(private val fileRecordingParams: FileRecordingParams) : JibriService() {
    /**
     * The [Logger] for this class
     */
    private val logger = Logger.getLogger(this::class.qualifiedName)
    /**
     * Used for the selenium interaction
     */
    private val jibriSelenium = JibriSelenium(
        JibriSeleniumOptions(
            fileRecordingParams.callParams, urlParams = RECORDING_URL_OPTIONS),
        Executors.newSingleThreadScheduledExecutor(NameableThreadFactory("JibriSelenium"))
    )
    /**
     * The [FfmpegCapturer] that will be used to capture media from the call and write it to a file
     */
    private val capturer = FfmpegCapturer()
    /**
     * The [Sink] this class will use to model the file on the filesystem
     */
    private var sink: Sink
    /**
     * If ffmpeg dies for some reason, we want to restart it.  This [ScheduledExecutorService]
     * will run the process monitor in a separate thread so it can check that it's running on its own
     */
    private val executor: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor(NameableThreadFactory("FileRecordingJibriService"))
    /**
     * The handle to the scheduled process monitor task, which we use to
     * cancel the task
     */
    private var processMonitorTask: ScheduledFuture<*>? = null

    init {
        sink = FileSink(fileRecordingParams.recordingDirectory, fileRecordingParams.callParams.callUrlInfo.callName)
        jibriSelenium.addStatusHandler(this::publishStatus)
    }

    override fun start(): Boolean {
        if (!jibriSelenium.joinCall(
                fileRecordingParams.callParams.callUrlInfo.callName, fileRecordingParams.callLoginParams)) {
            logger.error("Selenium failed to join the call")
            return false
        }
        if (!capturer.start(sink)) {
            logger.error("Capturer failed to start")
            return false
        }
        val processMonitor = createCaptureMonitor(capturer)
        processMonitorTask = executor.scheduleAtFixedRate(processMonitor, 30, 10, TimeUnit.SECONDS)
        return true
    }

    private fun createCaptureMonitor(process: Capturer): ProcessMonitor {
        var numRestarts = 0
        return ProcessMonitor(process) { exitCode ->
            if (exitCode != null) {
                logger.error("Capturer process is no longer healthy.  It exited with code $exitCode")
            } else {
                logger.error("Capturer process is no longer healthy but it is still running, stopping it now")
            }
            if (numRestarts == FFMPEG_RESTART_ATTEMPTS) {
                logger.error("Giving up on restarting the capturer")
                publishStatus(JibriServiceStatus.ERROR)
            } else {
                numRestarts++
                // Re-create the sink here because we want a new filename
                //TODO: we can run into an issue here where this takes a while and the monitor task runs again
                // and, while ffmpeg is still starting up, detects it as 'not encoding' for the second time
                // and shuts it down
                sink = FileSink(fileRecordingParams.recordingDirectory, fileRecordingParams.callParams.callUrlInfo.callName)
                process.stop()
                if (!process.start(sink)) {
                    logger.error("Capture failed to restart, giving up")
                    publishStatus(JibriServiceStatus.ERROR)
                }
            }
        }
    }

    override fun stop() {
        processMonitorTask?.cancel(false)
        logger.info("Stopping capturer")
        capturer.stop()
        logger.info("Quitting selenium")
        val participants = jibriSelenium.getParticipants()
        logger.info("Participants in this recording: $participants")
        val metadata = RecordingMetadata(fileRecordingParams.callParams.callUrlInfo.callUrl, participants)
        jacksonObjectMapper()
            .writeValue(File(fileRecordingParams.recordingDirectory, "metadata"), metadata)
        jibriSelenium.leaveCallAndQuitBrowser()
        logger.info("Finalizing the recording")
        finalize()
    }

    /**
     * Helper to execute the finalize script and wait for its completion.
     * NOTE that this will block for however long the finalize script takes
     * to complete (by design)
     */
    private fun finalize() {
        try {
            val finalizeProc = Runtime.getRuntime()
                .exec("${fileRecordingParams.finalizeScriptPath} ${fileRecordingParams.recordingDirectory}")
            finalizeProc.waitFor()
            logger.info("Recording finalize script finished with exit " +
                    "value: ${finalizeProc.exitValue()}")
        } catch (e: IOException) {
            logger.error("Failed to run finalize script: $e")
        }
    }
}
