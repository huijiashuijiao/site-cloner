package com.example.sitecloner.model;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

public class CrawlTask {
    public enum Status { QUEUED, RUNNING, SUCCEEDED, FAILED, CANCELLED }

    private final String id;
    private final CrawlRequest request;
    private volatile CrawlResult result;
    private volatile Status status;
    private volatile Instant startTime;
    private volatile Instant endTime;
    private volatile String errorMessage;
    private volatile String threadName;

    public CrawlTask(CrawlRequest request) {
        this.id = UUID.randomUUID().toString();
        this.request = request;
        this.status = Status.QUEUED;
    }

    public String getId() { return id; }
    public CrawlRequest getRequest() { return request; }
    public CrawlResult getResult() { return result; }
    public void setResult(CrawlResult result) { this.result = result; }
    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }
    public Instant getStartTime() { return startTime; }
    public void setStartTime(Instant startTime) { this.startTime = startTime; }
    public Instant getEndTime() { return endTime; }
    public void setEndTime(Instant endTime) { this.endTime = endTime; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    public String getThreadName() { return threadName; }
    public void setThreadName(String threadName) { this.threadName = threadName; }

    public long getPagesDownloaded() {
        return result == null ? 0 : result.getPagesDownloaded();
    }

    public long getAssetsDownloaded() {
        return result == null ? 0 : result.getAssetsDownloaded();
    }

    public int getErrorsCount() {
        return result == null ? 0 : result.getErrors().size();
    }

    public String getDuration() {
        Instant end = endTime != null ? endTime : Instant.now();
        Instant start = startTime != null ? startTime : end;
        Duration d = Duration.between(start, end);
        long s = d.getSeconds();
        long m = s / 60; s = s % 60;
        return (m > 0 ? (m + "m ") : "") + s + "s";
    }
}
