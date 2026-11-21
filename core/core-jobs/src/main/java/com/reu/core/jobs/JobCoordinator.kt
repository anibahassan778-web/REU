package com.reu.core.jobs

import com.reu.core.common.JobStatus
import com.reu.core.common.JobType
import com.reu.core.database.AnalysisJobEntity
import com.reu.core.database.ReuRepository
import com.reu.core.logging.ReuLogger
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class JobControlToken(
  val jobId: String,
  private val pausedFlag: MutableStateFlow<Boolean> = MutableStateFlow(false),
) {
  @Volatile var cancelled: Boolean = false
    internal set

  val isPaused: Boolean
    get() = pausedFlag.value

  fun pause() {
    pausedFlag.value = true
  }

  fun resume() {
    pausedFlag.value = false
  }

  suspend fun checkPoint() {
    if (cancelled) throw CancellationException("Operation cancelled by user")
    while (pausedFlag.value) {
      if (cancelled) throw CancellationException("Operation cancelled while paused")
      delay(150)
    }
  }
}

class JobCoordinator(
  private val repository: ReuRepository,
  private val logger: ReuLogger,
) {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val activeCoroutines = ConcurrentHashMap<String, Job>()
  private val activeTokens = ConcurrentHashMap<String, JobControlToken>()

  fun launchJob(
    projectId: String?,
    jobType: JobType,
    title: String,
    block: suspend (token: JobControlToken, updateProgress: suspend (Int, String) -> Unit) -> Unit,
  ): String {
    val jobId = UUID.randomUUID().toString()
    val token = JobControlToken(jobId)
    activeTokens[jobId] = token
    val startedAt = System.currentTimeMillis()

    val job =
      scope.launch {
        val initial =
          AnalysisJobEntity(
            id = jobId,
            projectId = projectId,
            jobType = jobType,
            title = title,
            status = JobStatus.RUNNING,
            progressPercent = 0,
            detailMessage = "Starting...",
            errorMessage = null,
            startedAtMs = startedAt,
            updatedAtMs = startedAt,
            finishedAtMs = null,
          )
        repository.upsertJob(initial)
        logger.info("Jobs", "Started [$jobType] $title", projectId)

        try {
          block(token) { pct, msg ->
            val now = System.currentTimeMillis()
            val status = if (token.isPaused) JobStatus.PAUSED else JobStatus.RUNNING
            repository.upsertJob(
              initial.copy(
                status = status,
                progressPercent = pct.coerceIn(0, 100),
                detailMessage = msg,
                updatedAtMs = now,
              ),
            )
          }
          val now = System.currentTimeMillis()
          repository.upsertJob(
            initial.copy(
              status = JobStatus.COMPLETED,
              progressPercent = 100,
              detailMessage = "Completed",
              updatedAtMs = now,
              finishedAtMs = now,
            ),
          )
          logger.info("Jobs", "Completed [$jobType] $title", projectId)
        } catch (ce: CancellationException) {
          val now = System.currentTimeMillis()
          repository.upsertJob(
            initial.copy(
              status = JobStatus.CANCELLED,
              detailMessage = ce.message ?: "Cancelled",
              updatedAtMs = now,
              finishedAtMs = now,
            ),
          )
          logger.warn("Jobs", "Cancelled [$jobType] $title", projectId)
        } catch (t: Throwable) {
          val now = System.currentTimeMillis()
          repository.upsertJob(
            initial.copy(
              status = JobStatus.FAILED,
              detailMessage = "Failed",
              errorMessage = t.message ?: t.javaClass.simpleName,
              updatedAtMs = now,
              finishedAtMs = now,
            ),
          )
          logger.error("Jobs", "Failed [$jobType] $title: ${t.message}", projectId)
        } finally {
          activeCoroutines.remove(jobId)
          activeTokens.remove(jobId)
        }
      }

    activeCoroutines[jobId] = job
    return jobId
  }

  fun cancelJob(jobId: String) {
    activeTokens[jobId]?.cancelled = true
    activeCoroutines[jobId]?.cancel()
  }

  fun pauseJob(jobId: String) {
    activeTokens[jobId]?.pause()
  }

  fun resumeJob(jobId: String) {
    activeTokens[jobId]?.resume()
  }
}
