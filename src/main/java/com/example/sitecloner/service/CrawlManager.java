package com.example.sitecloner.service;

import com.example.sitecloner.model.CrawlRequest;
import com.example.sitecloner.model.CrawlResult;
import com.example.sitecloner.model.CrawlTask;
import com.example.sitecloner.model.CrawlTaskEntity;
import com.example.sitecloner.repo.CrawlTaskRepository;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.time.Instant;
import java.util.Collection;
import java.util.Collections;
import java.util.concurrent.*;

@Service
public class CrawlManager {
    private final CrawlService crawlService;
    private final CrawlTaskRepository repo;
    private ExecutorService executor;
    private final ConcurrentHashMap<String, CrawlTask> tasks = new ConcurrentHashMap<String, CrawlTask>();
    private final ConcurrentHashMap<String, Future<?>> futures = new ConcurrentHashMap<String, Future<?>>();

    public CrawlManager(CrawlService crawlService, CrawlTaskRepository repo) {
        this.crawlService = crawlService;
        this.repo = repo;
    }

    @PostConstruct
    public void init() {
        int threads = Math.max(2, Runtime.getRuntime().availableProcessors() / 2);
        this.executor = new ThreadPoolExecutor(
                threads, threads,
                60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<Runnable>(100),
                new ThreadFactory() {
                    private final ThreadFactory df = Executors.defaultThreadFactory();
                    public Thread newThread(Runnable r) {
                        Thread t = df.newThread(r);
                        t.setName("site-crawler-" + t.getId());
                        t.setDaemon(true);
                        return t;
                    }
                },
                new ThreadPoolExecutor.AbortPolicy());
    }

    @PreDestroy
    public void shutdown() {
        if (executor != null) executor.shutdownNow();
    }

    public CrawlTask submit(CrawlRequest request) {
        final CrawlTask task = new CrawlTask(request);
        tasks.put(task.getId(), task);
        // 持久化初始任务（未开始）
        final CrawlTaskEntity entity = new CrawlTaskEntity();
        entity.setTaskUuid(task.getId());
        entity.setStatus("未开始");
        entity.setStartUrl(request.getStartUrl());
        entity.setSameDomain(request.isSameDomain());
        entity.setMaxPages(request.getMaxPages());
        entity.setMaxDepth(request.getMaxDepth());
        entity.setDebugOnlyHome(request.isDebugOnlyHome());
        entity.setOutputName(request.getOutputName());
        entity.setTitleSuffix(request.getTitleSuffix());
        entity.setSitemapDomain(request.getSitemapDomain());
        // 以 JSON 形式保存替换规则
        if (request.getReplaceRules() != null) {
            try {
                com.fasterxml.jackson.databind.ObjectMapper om = new com.fasterxml.jackson.databind.ObjectMapper();
                entity.setReplaceRulesJson(om.writeValueAsString(request.getReplaceRules()));
            } catch (Exception ignore) {}
        }
        repo.save(entity);

        Future<?> f = executor.submit(new Runnable() {
            public void run() {
                task.setStatus(CrawlTask.Status.RUNNING);
                task.setStartTime(Instant.now());
                task.setThreadName(Thread.currentThread().getName());
                try {
                    entity.setStatus("采集中");
                    entity.setStartTime(task.getStartTime());
                    entity.setThreadName(task.getThreadName());
                    repo.save(entity);

                    CrawlResult result = crawlService.crawl(request);
                    task.setResult(result);
                    task.setStatus(CrawlTask.Status.SUCCEEDED);

                    entity.setStatus("采集完成");
                    entity.setOutputDir(result.getOutputDirectory());
                    entity.setPagesDownloaded((int) result.getPagesDownloaded());
                    entity.setAssetsDownloaded((int) result.getAssetsDownloaded());
                    entity.setEndTime(Instant.now());
                    entity.setErrorMessage(null);
                    if (result.getErrors() != null && !result.getErrors().isEmpty()) {
                        entity.setErrorsJson(String.join("\n", result.getErrors()));
                    }
                    repo.save(entity);
                } catch (Throwable ex) {
                    task.setErrorMessage(ex.getMessage());
                    task.setStatus(CrawlTask.Status.FAILED);

                    entity.setStatus("采集失败");
                    entity.setEndTime(Instant.now());
                    entity.setErrorMessage(ex.getMessage());
                    repo.save(entity);
                } finally {
                    task.setEndTime(Instant.now());
                }
            }
        });
        futures.put(task.getId(), f);
        return task;
    }

    public boolean cancel(String id) {
        Future<?> f = futures.get(id);
        if (f == null) return false;
        boolean ok = f.cancel(true);
        try {
            CrawlTaskEntity e = repo.findByTaskUuid(id);
            if (e != null) {
                e.setStatus("已取消");
                e.setEndTime(Instant.now());
                repo.save(e);
            }
        } catch (Exception ignore) {}
        CrawlTask t = tasks.get(id);
        if (t != null) t.setStatus(CrawlTask.Status.FAILED);
        return ok;
    }

    public String submitMock(final com.example.sitecloner.model.CrawlRequest form) {
        final String uuid = java.util.UUID.randomUUID().toString();
        final com.example.sitecloner.model.CrawlTaskEntity entity = new com.example.sitecloner.model.CrawlTaskEntity();
        entity.setTaskUuid(uuid);
        entity.setStatus("未开始");
        entity.setStartUrl(form.getStartUrl());
        entity.setSameDomain(form.isSameDomain());
        entity.setMaxPages(form.getMaxPages());
        entity.setMaxDepth(form.getMaxDepth());
        entity.setDebugOnlyHome(form.isDebugOnlyHome());
        entity.setOutputName(form.getOutputName());
        entity.setTitleSuffix(form.getTitleSuffix());
        entity.setSitemapDomain(form.getSitemapDomain());
        if (form.getReplaceRules() != null) {
            try {
                com.fasterxml.jackson.databind.ObjectMapper om = new com.fasterxml.jackson.databind.ObjectMapper();
                entity.setReplaceRulesJson(om.writeValueAsString(form.getReplaceRules()));
            } catch (Exception ignore) {}
        }
        repo.save(entity);

        Future<?> f = executor.submit(new Runnable() {
            public void run() {
                try {
                    entity.setStatus("采集中");
                    entity.setStartTime(java.time.Instant.now());
                    entity.setThreadName(Thread.currentThread().getName());
                    repo.save(entity);
                    try { Thread.sleep(20000L); } catch (InterruptedException ignored) {}
                    entity.setStatus("采集完成");
                    entity.setEndTime(java.time.Instant.now());
                    repo.save(entity);
                } catch (Throwable ex) {
                    entity.setStatus("采集失败");
                    entity.setErrorMessage(ex.getMessage());
                    entity.setEndTime(java.time.Instant.now());
                    repo.save(entity);
                }
            }
        });
        futures.put(uuid, f);
        return uuid;
    }

    public String submitMock(final String startUrl) {
        final String uuid = java.util.UUID.randomUUID().toString();
        final com.example.sitecloner.model.CrawlTaskEntity entity = new com.example.sitecloner.model.CrawlTaskEntity();
        entity.setTaskUuid(uuid);
        entity.setStatus("未开始");
        entity.setStartUrl(startUrl);
        repo.save(entity);

        Future<?> f = executor.submit(new Runnable() {
            public void run() {
                try {
                    entity.setStatus("采集中");
                    entity.setStartTime(java.time.Instant.now());
                    entity.setThreadName(Thread.currentThread().getName());
                    repo.save(entity);

                    try { Thread.sleep(20000L); } catch (InterruptedException ignored) {}

                    entity.setStatus("采集完成");
                    entity.setEndTime(java.time.Instant.now());
                    repo.save(entity);
                } catch (Throwable ex) {
                    entity.setStatus("采集失败");
                    entity.setErrorMessage(ex.getMessage());
                    entity.setEndTime(java.time.Instant.now());
                    repo.save(entity);
                }
            }
        });
        futures.put(uuid, f);
        return uuid;
    }

    public CrawlTask get(String id) {
        return tasks.get(id);
    }

    public Collection<CrawlTask> list() {
        return Collections.unmodifiableCollection(tasks.values());
    }
}
