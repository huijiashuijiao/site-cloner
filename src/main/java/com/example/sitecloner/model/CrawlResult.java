package com.example.sitecloner.model;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

public class CrawlResult {

	private String outputDirectory;
	private int pagesDownloaded;
	private int assetsDownloaded;
	private Duration elapsed;
	private final List<String> errors = new ArrayList<>();

	// JS 中识别出的需要下载的页面 URL（绝对地址字符串）
	private final Set<String> jsPages = new HashSet<>();

	// 已保存的页面（绝对 URL），用于生成 sitemap.xml
	private final Set<String> pages = new TreeSet<>();

	// 本次任务内已下载过的资产（绝对 URL 字符串），用于去重
	private final Set<String> downloadedAssets = new HashSet<>();

	// 已对其执行过 JS 资产扫描的 JS 资源（绝对 URI 字符串）
	private final Set<String> processedJsUris = new HashSet<>();

	public String getOutputDirectory() {
		return outputDirectory;
	}

	public void setOutputDirectory(String outputDirectory) {
		this.outputDirectory = outputDirectory;
	}

	public int getPagesDownloaded() {
		return pagesDownloaded;
	}

	public void setPagesDownloaded(int pagesDownloaded) {
		this.pagesDownloaded = pagesDownloaded;
	}

	public int getAssetsDownloaded() {
		return assetsDownloaded;
	}

	public void setAssetsDownloaded(int assetsDownloaded) {
		this.assetsDownloaded = assetsDownloaded;
	}

	public Duration getElapsed() {
		return elapsed;
	}

	public void setElapsed(Duration elapsed) {
		this.elapsed = elapsed;
	}

	public List<String> getErrors() {
		return errors;
	}

	public void addError(String message) {
		this.errors.add(message);
	}

	public Set<String> getJsPages() {
		return jsPages;
	}

	public void addJsPage(String url) {
		if (url != null && !url.isEmpty()) {
			jsPages.add(url);
		}
	}

	public Set<String> getPages() {
		return pages;
	}

	public void addPage(String url) {
		if (url != null && !url.isEmpty()) {
			pages.add(url);
		}
	}

	public boolean hasAsset(String absUrl) {
		return absUrl != null && downloadedAssets.contains(absUrl);
	}

	public boolean tryMarkAsset(String absUrl) {
		if (absUrl == null || absUrl.isEmpty()) return false;
		return downloadedAssets.add(absUrl);
	}

	public boolean tryMarkJsProcessed(String jsUri) {
		if (jsUri == null || jsUri.isEmpty()) return false;
		return processedJsUris.add(jsUri);
	}
}


