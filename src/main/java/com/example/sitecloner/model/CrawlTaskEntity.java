package com.example.sitecloner.model;

import javax.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "crawl_task")
public class CrawlTaskEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "task_uuid", nullable = false, unique = true, length = 64)
    private String taskUuid;

    @Column(name = "status", length = 20)
    private String status;

    @Column(name = "start_url", length = 1000)
    private String startUrl;

    @Column(name = "same_domain")
    private Boolean sameDomain;

    @Column(name = "max_pages")
    private Integer maxPages;

    @Column(name = "max_depth")
    private Integer maxDepth;

    @Column(name = "debug_only_home")
    private Boolean debugOnlyHome;

    @Column(name = "output_dir", length = 1024)
    private String outputDir;

    @Column(name = "pages_downloaded")
    private Integer pagesDownloaded;

    @Column(name = "assets_downloaded")
    private Integer assetsDownloaded;

    @Column(name = "errors", columnDefinition = "TEXT")
    private String errorsJson;

    @Column(name = "start_time")
    private Instant startTime;

    @Column(name = "end_time")
    private Instant endTime;

    @Column(name = "thread_name", length = 100)
    private String threadName;

    @Column(name = "error_message", length = 2000)
    private String errorMessage;

    // 额外保存页面填写数据
    @Column(name = "output_name", length = 255)
    private String outputName;

    @Column(name = "title_suffix", length = 255)
    private String titleSuffix;

    @Column(name = "sitemap_domain", length = 512)
    private String sitemapDomain;

    @Column(name = "replace_rules", columnDefinition = "TEXT")
    private String replaceRulesJson;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getTaskUuid() { return taskUuid; }
    public void setTaskUuid(String taskUuid) { this.taskUuid = taskUuid; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getStartUrl() { return startUrl; }
    public void setStartUrl(String startUrl) { this.startUrl = startUrl; }
    public Boolean getSameDomain() { return sameDomain; }
    public void setSameDomain(Boolean sameDomain) { this.sameDomain = sameDomain; }
    public Integer getMaxPages() { return maxPages; }
    public void setMaxPages(Integer maxPages) { this.maxPages = maxPages; }
    public Integer getMaxDepth() { return maxDepth; }
    public void setMaxDepth(Integer maxDepth) { this.maxDepth = maxDepth; }
    public Boolean getDebugOnlyHome() { return debugOnlyHome; }
    public void setDebugOnlyHome(Boolean debugOnlyHome) { this.debugOnlyHome = debugOnlyHome; }
    public String getOutputDir() { return outputDir; }
    public void setOutputDir(String outputDir) { this.outputDir = outputDir; }
    public Integer getPagesDownloaded() { return pagesDownloaded; }
    public void setPagesDownloaded(Integer pagesDownloaded) { this.pagesDownloaded = pagesDownloaded; }
    public Integer getAssetsDownloaded() { return assetsDownloaded; }
    public void setAssetsDownloaded(Integer assetsDownloaded) { this.assetsDownloaded = assetsDownloaded; }
    public String getErrorsJson() { return errorsJson; }
    public void setErrorsJson(String errorsJson) { this.errorsJson = errorsJson; }
    public Instant getStartTime() { return startTime; }
    public void setStartTime(Instant startTime) { this.startTime = startTime; }
    public Instant getEndTime() { return endTime; }
    public void setEndTime(Instant endTime) { this.endTime = endTime; }
    public String getThreadName() { return threadName; }
    public void setThreadName(String threadName) { this.threadName = threadName; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    public String getOutputName() { return outputName; }
    public void setOutputName(String outputName) { this.outputName = outputName; }
    public String getTitleSuffix() { return titleSuffix; }
    public void setTitleSuffix(String titleSuffix) { this.titleSuffix = titleSuffix; }
    public String getSitemapDomain() { return sitemapDomain; }
    public void setSitemapDomain(String sitemapDomain) { this.sitemapDomain = sitemapDomain; }
    public String getReplaceRulesJson() { return replaceRulesJson; }
    public void setReplaceRulesJson(String replaceRulesJson) { this.replaceRulesJson = replaceRulesJson; }
}
