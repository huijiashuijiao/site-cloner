package com.example.sitecloner.controller;

import com.example.sitecloner.model.CrawlRequest;
import com.example.sitecloner.model.CrawlResult;
import com.example.sitecloner.model.CrawlTask;
import com.example.sitecloner.repo.CrawlTaskRepository;
import com.example.sitecloner.service.CrawlManager;
import com.example.sitecloner.service.CrawlService;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

@Controller
public class AdminController {

	private final CrawlService crawlService;
    private final CrawlManager crawlManager;
    private final CrawlTaskRepository taskRepo;

	public AdminController(CrawlService crawlService, CrawlManager crawlManager, CrawlTaskRepository taskRepo) {
		this.crawlService = crawlService;
        this.crawlManager = crawlManager;
        this.taskRepo = taskRepo;
	}

	@GetMapping("/")
	public String index(Model model) {
		CrawlRequest form = new CrawlRequest();
		model.addAttribute("form", form);
		return "index";
	}

	@PostMapping("/crawl")
	public String crawl(@ModelAttribute("form") CrawlRequest form,
	                   BindingResult bindingResult,
	                   Model model) {
		// 简单兜底校验，避免空 URL
		if (form.getStartUrl() == null || form.getStartUrl().trim().isEmpty()) {
			bindingResult.rejectValue("startUrl", "startUrl.empty", "起始 URL 不能为空");
			return "index";
		}
		CrawlResult result = crawlService.crawl(form);
		model.addAttribute("result", result);
		return "result";
	}

    // 异步提交采集任务（多站点并发）
    @PostMapping("/crawl/async")
    @ResponseBody
    public ResponseEntity<String> crawlAsync(@RequestBody CrawlRequest form) {
        if (form == null || form.getStartUrl() == null || form.getStartUrl().trim().isEmpty()) {
            return ResponseEntity.badRequest().body("startUrl 不能为空");
        }
        // 打印前端提交数据
        System.out.println("[FORM][ASYNC] startUrl=" + form.getStartUrl()
                + ", sameDomain=" + form.isSameDomain()
                + ", maxDepth=" + form.getMaxDepth()
                + ", maxPages=" + form.getMaxPages()
                + ", debugOnlyHome=" + form.isDebugOnlyHome()
                + ", outputName=" + form.getOutputName()
                + ", titleSuffix=" + form.getTitleSuffix()
                + ", sitemapDomain=" + form.getSitemapDomain()
        );
        java.util.List<com.example.sitecloner.model.ReplacementRule> rr = form.getReplaceRules();
        if (rr != null) {
            System.out.println("[FORM][ASYNC][REPLACE-RULES] size=" + rr.size());
            for (int i = 0; i < rr.size(); i++) {
                com.example.sitecloner.model.ReplacementRule r = rr.get(i);
                if (r == null) continue;
                System.out.println("  [" + i + "] find='" + r.getFind() + "' -> replaceWith='" + r.getReplaceWith() + "'");
            }
        }
        CrawlTask task = crawlManager.submit(form);
        return ResponseEntity.ok(task.getId());
    }

    // 查询任务详情
    @GetMapping("/crawl/tasks/{id}")
    @ResponseBody
    public ResponseEntity<Object> getTask(@PathVariable("id") String id) {
        CrawlTask t = crawlManager.get(id);
        if (t == null) return ResponseEntity.status(HttpStatus.NOT_FOUND).body("任务不存在");
        return ResponseEntity.ok(t);
    }

    // 列出所有任务
    @GetMapping("/crawl/tasks")
    @ResponseBody
    public ResponseEntity<Object> listTasks() {
        return ResponseEntity.ok(crawlManager.list());
    }

    @GetMapping("/tasks")
    public String tasksPage(Model model) {
        model.addAttribute("tasks", crawlManager.list());
        return "tasks";
    }

    @GetMapping("/tasks/db")
    public String tasksDbPage(Model model) {
        model.addAttribute("tasks", taskRepo.findAll());
        return "tasks_db";
    }

	// 调试：读取 test.js，重写链接后输出为 test2.js 并返回下载
	@GetMapping("/debug/rewrite-js")
	public ResponseEntity<Resource> debugRewriteJs(@RequestParam(value = "pageUrl", required = false, defaultValue = "https://wb.jiangsu.gov.cn/") String pageUrl,
	                                              @RequestParam(value = "file", required = false, defaultValue = "test.js") String file) {
		try {
			// 寻找 test.js：优先当前工作目录，其次上一级目录
			Path jsPath = Paths.get(file);
			if (!Files.exists(jsPath)) {
				Path parent = Paths.get("..", file);
				if (Files.exists(parent)) {
					jsPath = parent;
				}
			}
			if (!Files.exists(jsPath)) {
				return ResponseEntity.status(HttpStatus.NOT_FOUND)
						.body(new ByteArrayResource(("未找到JS文件: " + jsPath.toAbsolutePath()).getBytes(StandardCharsets.UTF_8)));
			}

			String jsText = new String(Files.readAllBytes(jsPath), StandardCharsets.UTF_8);
			URI pageUri = URI.create(pageUrl);

			// 输出目录与相对路径基准
			String host = pageUri.getHost() == null ? "debug-host" : pageUri.getHost();
			Path outDir = Paths.get("output", "debug");
			Files.createDirectories(outDir.resolve(host));
			Path currentLocalHtml = outDir.resolve(Paths.get(host, "index.html"));

			CrawlResult debugResult = new CrawlResult();
			String rewritten = crawlService.rewriteJsLinksInContent(jsText, pageUri, outDir, currentLocalHtml, debugResult);

			// 写入 test2.js（与源文件同目录）
			Path outFile = jsPath.getParent() == null ? Paths.get("test2.js") : jsPath.getParent().resolve("test2.js");
			Files.write(outFile, rewritten.getBytes(StandardCharsets.UTF_8));

			// 输出 JS 中统计出来的页面 URL 与数量，并写入侧文件 test2.pages.txt 便于查看
			java.util.LinkedHashSet<String> pages = new java.util.LinkedHashSet<String>(debugResult.getJsPages());
			System.out.println("[DEBUG-JS-PAGE][TOTAL] " + pages.size());
			for (String u : pages) {
				System.out.println("[DEBUG-JS-PAGE][URL] " + u);
			}
			Path pagesFile = outFile.getParent() == null ? Paths.get("test2.pages.txt") : outFile.getParent().resolve("test2.pages.txt");
			StringBuilder sb = new StringBuilder();
			sb.append("TOTAL=").append(pages.size()).append('\n');
			for (String u : pages) sb.append(u).append('\n');
			Files.write(pagesFile, sb.toString().getBytes(StandardCharsets.UTF_8));

			ByteArrayResource res = new ByteArrayResource(rewritten.getBytes(StandardCharsets.UTF_8));
			HttpHeaders headers = new HttpHeaders();
			headers.setContentType(MediaType.parseMediaType("application/javascript; charset=UTF-8"));
			headers.set(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"test2.js\"");
			return new ResponseEntity<>(res, headers, HttpStatus.OK);
		} catch (Exception ex) {
			return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
					.body(new ByteArrayResource(("调试重写失败: " + ex.getMessage()).getBytes(StandardCharsets.UTF_8)));
		}
	}

    // 测试：统一的模拟任务提交（支持 JSON 或表单参数）。
    // - 若为 JSON，请设置 Content-Type: application/json；若漏传 Content-Type，也会尝试手动解析请求体。
    @PostMapping("/crawl/mock")
    @ResponseBody
    public ResponseEntity<String> mockUnified(javax.servlet.http.HttpServletRequest request,
                                              @RequestBody(required = false) CrawlRequest form,
                                              @RequestParam(value = "startUrl", required = false) String startUrl) {
        try {
            String ct = request.getContentType();
            System.out.println("[MOCK][CT] " + ct);
            // 若没有反序列化出 JSON，且请求体存在，则手动读取尝试解析为 JSON
            if (form == null) {
                try {
                    java.io.BufferedReader reader = request.getReader();
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) { sb.append(line); }
                    String body = sb.toString();
                    if (body != null) body = body.trim();
                    if (body != null && body.startsWith("{")) {
                        System.out.println("[MOCK][RAW-BODY] " + (body.length() > 500 ? body.substring(0,500)+"..." : body));
                        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                        form = mapper.readValue(body, CrawlRequest.class);
                    }
                } catch (Exception ignore) {}
            }

            if (form != null && (form.getStartUrl() != null && !form.getStartUrl().trim().isEmpty())) {
                System.out.println("[FORM][MOCK] startUrl=" + form.getStartUrl()
                        + ", sameDomain=" + form.isSameDomain()
                        + ", maxDepth=" + form.getMaxDepth()
                        + ", maxPages=" + form.getMaxPages()
                        + ", debugOnlyHome=" + form.isDebugOnlyHome()
                        + ", outputName=" + form.getOutputName()
                        + ", titleSuffix=" + form.getTitleSuffix()
                        + ", sitemapDomain=" + form.getSitemapDomain()
                );
                java.util.List<com.example.sitecloner.model.ReplacementRule> rr = form.getReplaceRules();
                if (rr != null) {
                    System.out.println("[FORM][MOCK][REPLACE-RULES] size=" + rr.size());
                    for (int i = 0; i < rr.size(); i++) {
                        com.example.sitecloner.model.ReplacementRule r = rr.get(i);
                        if (r == null) continue;
                        System.out.println("  [" + i + "] find='" + r.getFind() + "' -> replaceWith='" + r.getReplaceWith() + "'");
                    }
                }
                String id = crawlManager.submitMock(form);
                return ResponseEntity.ok(id);
            }

            // 退化为仅 startUrl 的表单参数模式
            if (startUrl == null || startUrl.trim().isEmpty()) {
                return ResponseEntity.badRequest().body("startUrl 不能为空");
            }
            String id = crawlManager.submitMock(startUrl.trim());
            return ResponseEntity.ok(id);
        } catch (Exception ex) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("提交失败: " + ex.getMessage());
        }
    }
}


