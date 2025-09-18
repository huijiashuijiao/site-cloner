package com.example.sitecloner;

import com.example.sitecloner.config.StorageProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(StorageProperties.class)
public class SiteClonerApplication {

	public static void main(String[] args) {
		SpringApplication.run(SiteClonerApplication.class, args);
	}
}


