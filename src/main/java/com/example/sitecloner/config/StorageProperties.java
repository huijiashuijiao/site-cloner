package com.example.sitecloner.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "sitecloner.storage")
public class StorageProperties {

	// 输出根目录，支持通过外部配置文件覆盖
	private String outputBaseDir = "output";

	public String getOutputBaseDir() {
		return outputBaseDir;
	}

	public void setOutputBaseDir(String outputBaseDir) {
		this.outputBaseDir = outputBaseDir;
	}
}
