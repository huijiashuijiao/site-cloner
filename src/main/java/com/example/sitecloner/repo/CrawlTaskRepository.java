package com.example.sitecloner.repo;

import com.example.sitecloner.model.CrawlTaskEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CrawlTaskRepository extends JpaRepository<CrawlTaskEntity, Long> {
    CrawlTaskEntity findByTaskUuid(String taskUuid);
}
