package com.example.sitecloner.model;

import java.util.ArrayList;
import java.util.List;

public class CrawlRequest {

	private String startUrl;

	private boolean sameDomain = true;

	private int maxDepth = 5;

	private int maxPages = 500;

	private String outputName; // 可选自定义输出文件夹名

	// 页面标题后缀（用于“原标题-后缀”与 H1 注入）
	private String titleSuffix;

	// 调试：仅下载首页及其资源
	private boolean debugOnlyHome = false;

	// 站点地图域名（用于生成 sitemap.xml 的 <loc> 前缀）
	private String sitemapDomain;

	// 文本替换规则（按顺序应用）
	private List<ReplacementRule> replaceRules = new ArrayList<>();

	public String getStartUrl() {
		return startUrl;
	}

	public void setStartUrl(String startUrl) {
		this.startUrl = startUrl;
	}

	public boolean isSameDomain() {
		return sameDomain;
	}

	public void setSameDomain(boolean sameDomain) {
		this.sameDomain = sameDomain;
	}

	public int getMaxDepth() {
		return maxDepth;
	}

	public void setMaxDepth(int maxDepth) {
		this.maxDepth = maxDepth;
	}

	public int getMaxPages() {
		return maxPages;
	}

	public void setMaxPages(int maxPages) {
		this.maxPages = maxPages;
	}

	public String getOutputName() {
		return outputName;
	}

	public void setOutputName(String outputName) {
		this.outputName = outputName;
	}

	public boolean isDebugOnlyHome() {
		return debugOnlyHome;
	}

	public void setDebugOnlyHome(boolean debugOnlyHome) {
		this.debugOnlyHome = debugOnlyHome;
	}

	public String getTitleSuffix() {
		return titleSuffix;
	}

	public void setTitleSuffix(String titleSuffix) {
		this.titleSuffix = titleSuffix;
	}

	public String getSitemapDomain() {
		return sitemapDomain;
	}

	public void setSitemapDomain(String sitemapDomain) {
		this.sitemapDomain = sitemapDomain;
	}

	public List<ReplacementRule> getReplaceRules() {
		return replaceRules;
	}

	public void setReplaceRules(List<ReplacementRule> replaceRules) {
		this.replaceRules = replaceRules;
	}
}


