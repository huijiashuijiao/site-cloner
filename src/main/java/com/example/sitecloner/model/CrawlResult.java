package com.example.sitecloner.model;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class CrawlResult {

	private String outputDirectory;
	private int pagesDownloaded;
	private int assetsDownloaded;
	private Duration elapsed;
	private final List<String> errors = new ArrayList<>();

	// JS 中识别出的需要下载的页面 URL（绝对地址字符串）
	private final Set<String> jsPages = new HashSet<>();

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
}


