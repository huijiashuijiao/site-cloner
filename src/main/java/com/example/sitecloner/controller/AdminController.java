package com.example.sitecloner.controller;

import com.example.sitecloner.model.CrawlRequest;
import com.example.sitecloner.model.CrawlResult;
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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

@Controller
public class AdminController {

	private final CrawlService crawlService;

	public AdminController(CrawlService crawlService) {
		this.crawlService = crawlService;
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

			String rewritten = crawlService.rewriteJsLinksInContent(jsText, pageUri, outDir, currentLocalHtml, new CrawlResult());

			// 写入 test2.js（与源文件同目录）
			Path outFile = jsPath.getParent() == null ? Paths.get("test2.js") : jsPath.getParent().resolve("test2.js");
			Files.write(outFile, rewritten.getBytes(StandardCharsets.UTF_8));

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
}


