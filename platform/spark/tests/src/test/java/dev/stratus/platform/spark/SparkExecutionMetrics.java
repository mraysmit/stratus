// Copyright 2026 Mark Andrew Ray-Smith Cityline Ltd
// SPDX-License-Identifier: Apache-2.0

package dev.stratus.platform.spark;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import org.apache.spark.executor.TaskMetrics;
import org.apache.spark.scheduler.SparkListener;
import org.apache.spark.scheduler.SparkListenerJobEnd;
import org.apache.spark.scheduler.SparkListenerJobStart;
import org.apache.spark.scheduler.SparkListenerTaskEnd;

/** Aggregates scheduler and executor metrics by the SQL job-group operation ID. */
final class SparkExecutionMetrics extends SparkListener {

    private static final String JOB_GROUP = "spark.jobGroup.id";

    private final Map<Integer, String> jobs = new ConcurrentHashMap<>();
    private final Map<Integer, Long> jobStarts = new ConcurrentHashMap<>();
    private final Map<Integer, String> stages = new ConcurrentHashMap<>();
    private final Map<String, MutableMetrics> operations = new ConcurrentHashMap<>();

    @Override
    public void onJobStart(SparkListenerJobStart event) {
        String operationId = event.properties() == null
                ? null : event.properties().getProperty(JOB_GROUP);
        if (operationId == null) {
            return;
        }
        jobs.put(event.jobId(), operationId);
        jobStarts.put(event.jobId(), event.time());
        MutableMetrics metrics = operations.computeIfAbsent(operationId, ignored -> new MutableMetrics());
        metrics.jobs.increment();
        var iterator = event.stageIds().iterator();
        while (iterator.hasNext()) {
            int stageId = (Integer) iterator.next();
            stages.put(stageId, operationId);
            metrics.stageIds.add(stageId);
        }
    }

    @Override
    public void onJobEnd(SparkListenerJobEnd event) {
        String operationId = jobs.get(event.jobId());
        Long startedAt = jobStarts.get(event.jobId());
        if (operationId != null && startedAt != null) {
            operations.get(operationId).schedulerMillis.add(Math.max(0L, event.time() - startedAt));
        }
    }

    @Override
    public void onTaskEnd(SparkListenerTaskEnd event) {
        String operationId = stages.get(event.stageId());
        TaskMetrics task = event.taskMetrics();
        if (operationId == null || task == null) {
            return;
        }
        MutableMetrics metrics = operations.get(operationId);
        metrics.tasks.increment();
        metrics.executorRunMillis.add(task.executorRunTime());
        metrics.executorCpuNanos.add(task.executorCpuTime());
        metrics.deserializeMillis.add(task.executorDeserializeTime());
        metrics.resultSerializationMillis.add(task.resultSerializationTime());
        metrics.jvmGcMillis.add(task.jvmGCTime());
        metrics.resultBytes.add(task.resultSize());
        metrics.memorySpillBytes.add(task.memoryBytesSpilled());
        metrics.diskSpillBytes.add(task.diskBytesSpilled());
        metrics.inputBytes.add(task.inputMetrics().bytesRead());
        metrics.inputRecords.add(task.inputMetrics().recordsRead());
        metrics.outputBytes.add(task.outputMetrics().bytesWritten());
        metrics.outputRecords.add(task.outputMetrics().recordsWritten());
        metrics.shuffleReadBytes.add(task.shuffleReadMetrics().totalBytesRead());
        metrics.shuffleReadRecords.add(task.shuffleReadMetrics().recordsRead());
        metrics.shuffleWriteBytes.add(task.shuffleWriteMetrics().bytesWritten());
        metrics.shuffleWriteRecords.add(task.shuffleWriteMetrics().recordsWritten());
    }

    MetricsSnapshot snapshot(String operationId) {
        MutableMetrics metrics = operations.get(operationId);
        return metrics == null ? MetricsSnapshot.EMPTY : metrics.snapshot();
    }

    private static final class MutableMetrics {
        private final LongAdder jobs = new LongAdder();
        private final Set<Integer> stageIds = ConcurrentHashMap.newKeySet();
        private final LongAdder tasks = new LongAdder();
        private final LongAdder schedulerMillis = new LongAdder();
        private final LongAdder executorRunMillis = new LongAdder();
        private final LongAdder executorCpuNanos = new LongAdder();
        private final LongAdder deserializeMillis = new LongAdder();
        private final LongAdder resultSerializationMillis = new LongAdder();
        private final LongAdder jvmGcMillis = new LongAdder();
        private final LongAdder resultBytes = new LongAdder();
        private final LongAdder inputBytes = new LongAdder();
        private final LongAdder inputRecords = new LongAdder();
        private final LongAdder outputBytes = new LongAdder();
        private final LongAdder outputRecords = new LongAdder();
        private final LongAdder shuffleReadBytes = new LongAdder();
        private final LongAdder shuffleReadRecords = new LongAdder();
        private final LongAdder shuffleWriteBytes = new LongAdder();
        private final LongAdder shuffleWriteRecords = new LongAdder();
        private final LongAdder memorySpillBytes = new LongAdder();
        private final LongAdder diskSpillBytes = new LongAdder();

        private MetricsSnapshot snapshot() {
            return new MetricsSnapshot(jobs.intValue(), stageIds.size(), tasks.intValue(),
                    schedulerMillis.sum(), executorRunMillis.sum(), executorCpuNanos.sum() / 1_000_000L,
                    deserializeMillis.sum(), resultSerializationMillis.sum(), jvmGcMillis.sum(),
                    resultBytes.sum(), inputBytes.sum(), inputRecords.sum(), outputBytes.sum(),
                    outputRecords.sum(), shuffleReadBytes.sum(), shuffleReadRecords.sum(),
                    shuffleWriteBytes.sum(), shuffleWriteRecords.sum(), memorySpillBytes.sum(),
                    diskSpillBytes.sum());
        }
    }

    record MetricsSnapshot(int listenerJobs, int listenerStages, int listenerTasks,
                           long schedulerMs, long executorRunMs, long executorCpuMs,
                           long deserializeMs, long resultSerializationMs, long jvmGcMs,
                           long resultBytes, long inputBytes, long inputRecords,
                           long outputBytes, long outputRecords, long shuffleReadBytes,
                           long shuffleReadRecords, long shuffleWriteBytes,
                           long shuffleWriteRecords, long memorySpillBytes, long diskSpillBytes) {

        private static final MetricsSnapshot EMPTY = new MetricsSnapshot(
                0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);

        String fields() {
            return "listenerJobs=" + listenerJobs + " listenerStages=" + listenerStages
                    + " listenerTasks=" + listenerTasks + " schedulerMs=" + schedulerMs
                    + " executorRunMs=" + executorRunMs + " executorCpuMs=" + executorCpuMs
                    + " deserializeMs=" + deserializeMs
                    + " resultSerializationMs=" + resultSerializationMs + " jvmGcMs=" + jvmGcMs
                    + " resultBytes=" + resultBytes + " inputBytes=" + inputBytes
                    + " inputRecords=" + inputRecords + " outputBytes=" + outputBytes
                    + " outputRecords=" + outputRecords + " shuffleReadBytes=" + shuffleReadBytes
                    + " shuffleReadRecords=" + shuffleReadRecords
                    + " shuffleWriteBytes=" + shuffleWriteBytes
                    + " shuffleWriteRecords=" + shuffleWriteRecords
                    + " memorySpillBytes=" + memorySpillBytes + " diskSpillBytes=" + diskSpillBytes;
        }
    }
}
