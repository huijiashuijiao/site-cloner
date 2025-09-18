package com.example.sitecloner.service;

import com.example.sitecloner.model.CrawlRequest;
import com.example.sitecloner.model.CrawlResult;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class CrawlService {

    // 正则：匹配 CSS/JS 文本中的 url(...) 模式
    private static final Pattern CSS_URL_PATTERN = Pattern.compile("url\\(\\s*(['\\\"]?)([^\\)\\'\\\"]+)\\1\\s*\\)", Pattern.CASE_INSENSITIVE);
    // 正则：仅匹配 JS 文本中被引号包裹的图片 URL（http(s) 或站内以 / 开头）
    private static final Pattern QUOTED_ASSET_PATTERN = Pattern.compile("['\\\"]((?:https?://|/)[^'\\\"]+\\.(?:png|jpe?g|gif|webp|svg|ico))(?:\\?[^'\\\"]*)?['\\\"]", Pattern.CASE_INSENSITIVE);
    // 正则：宽松匹配 JS 文本中未必被引号包裹的图片 URL 片段
    private static final Pattern JS_IMG_TOKEN_PATTERN = Pattern.compile("((?:https?://|/)[^\\s'\"<>]+\\.(?:png|jpe?g|gif|webp|svg|ico))(?:\\?[^\\s'\"<>]*)?", Pattern.CASE_INSENSITIVE);
    // 正则：定位 JS 字符串中的 href="..." 或 src="..."（用于 HTML 片段）
    private static final Pattern HREF_SRC_ATTR_PATTERN = Pattern.compile("(href|src)\\s*=\\s*(['\\\"])((?:https?://|/)[^'\\\"\\s<>]+)\\2", Pattern.CASE_INSENSITIVE);
    // 正则：定位 JS 字符串中的 href=\"...\" 或 src=\"...\"（转义引号场景）
    private static final Pattern ESC_HREF_SRC_ATTR_PATTERN = Pattern.compile("(href|src)\\s*=\\s*\\\\(['\\\"])((?:https?://|/)[^\\\\'\\\"\\s<>]+)\\\\\\\2", Pattern.CASE_INSENSITIVE);
    // 正则：定位 JS 中的 location.href = '...'
    private static final Pattern JS_LOC_HREF_PATTERN = Pattern.compile("(?:window\\.)?location\\.href\\s*=\\s*(['\\\"])((?:https?://|/)[^'\\\"\\s<>]+)\\1", Pattern.CASE_INSENSITIVE);
    // 正则：定位 JS 字符串中的 href=\"...\"（转义引号）
    private static final Pattern JS_LOC_HREF_ESC_PATTERN = Pattern.compile("(?:window\\.)?location\\.href\\s*=\\s*\\\\(['\\\"])((?:https?://|/)[^\\\\'\\\"\\s<>]+)\\\\\\\1", Pattern.CASE_INSENSITIVE);
    // 正则：定位 JS 中的 window.open('...'
    private static final Pattern JS_WINDOW_OPEN_PATTERN = Pattern.compile("window\\.open\\(\\s*(['\\\"])((?:https?://|/)[^'\\\"\\s<>]+)\\1", Pattern.CASE_INSENSITIVE);
    // 正则：定位 JS 中的 window.open(\"...\"（转义引号）
    private static final Pattern JS_WINDOW_OPEN_ESC_PATTERN = Pattern.compile("window\\.open\\(\\s*\\\\(['\\\"])((?:https?://|/)[^\\\\'\\\"\\s<>]+)\\\\\\\1", Pattern.CASE_INSENSITIVE);

	public CrawlResult crawl(CrawlRequest request) {
		Instant start = Instant.now();
		CrawlResult result = new CrawlResult();
		try {
			URI startUri = normalizeUri(request.getStartUrl());
			String baseHost = startUri.getHost();
			String timestamp = String.valueOf(System.currentTimeMillis());
			String outputDirName = (!isBlank(request.getOutputName()))
					? request.getOutputName()
					: (sanitizeFileName(baseHost) + "-" + timestamp);
			Path outputDir = Paths.get("output").resolve(outputDirName);
			Files.createDirectories(outputDir);

			breadthFirstCrawl(startUri, baseHost, request, outputDir, result);

			result.setOutputDirectory(outputDir.toAbsolutePath().toString());
		} catch (Exception e) {
			result.addError(e.getMessage());
		} finally {
			result.setElapsed(Duration.between(start, Instant.now()));
		}
		return result;
	}

	private void breadthFirstCrawl(URI startUri,
	                              String baseHost,
	                              CrawlRequest request,
	                              Path outputDir,
	                              CrawlResult result) throws IOException {
		ArrayDeque<URI> queue = new ArrayDeque<>();
		Set<String> visited = new HashSet<>();
		queue.add(startUri);

		int pages = 0;
		int depth = 0;
		int currentLevelCount = 1;
		int nextLevelCount = 0;

		while (!queue.isEmpty() && pages < request.getMaxPages() && depth < request.getMaxDepth()) {
			URI uri = queue.poll();
			currentLevelCount--;
			String key = uri.toString();
			if (visited.contains(key)) {
				if (currentLevelCount == 0) {
					depth++;
					currentLevelCount = nextLevelCount;
					nextLevelCount = 0;
				}
				continue;
			}
			visited.add(key);

			try {
				Document doc = Jsoup.connect(uri.toString())
						.userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36")
						.timeout(20000)
						.ignoreHttpErrors(true)
						.get();

				Path localHtmlPath = mapUriToLocalPath(outputDir, uri, true);
				Files.createDirectories(localHtmlPath.getParent());
                rewriteAndSaveHtml(doc, uri, outputDir, localHtmlPath, request, result);
				pages++;
				result.setPagesDownloaded(pages);

				if (request.isDebugOnlyHome()) {
					break;
				}

				Elements links = doc.select("a[href]");
				for (Element a : links) {
					String href = a.attr("abs:href");
					if (href == null || href.trim().isEmpty()) continue;
					URI next = safeUri(href);
					if (next == null) continue;
					if (request.isSameDomain() && !Objects.equals(next.getHost(), baseHost)) continue;
					if (!isLikelyHtml(next)) continue;
					if (visited.contains(next.toString())) continue;
					queue.add(next);
					nextLevelCount++;
				}
			} catch (Exception ex) {
				result.addError(uri + " -> " + ex.getMessage());
			}

			if (currentLevelCount == 0) {
				depth++;
				currentLevelCount = nextLevelCount;
				nextLevelCount = 0;
			}
		}
	}

    private void rewriteAndSaveHtml(Document doc,
                                   URI pageUri,
                                   Path outputDir,
                                   Path localHtmlPath,
                                   CrawlRequest request,
                                   CrawlResult result) throws IOException {
        // 处理常见资源: img[src], script[src], link[href]
        for (Element el : doc.select("img[src], script[src], link[href]")) {
			String attr = el.hasAttr("src") ? "src" : "href";
			String abs = el.attr("abs:" + attr);
			if (abs == null || abs.trim().isEmpty()) continue;
			URI resUri = safeUri(abs);
			if (resUri == null) continue;
            try {
                // 如果是 CSS 样式表，下载文本并解析其中的 url(...)
                boolean isStylesheet = "link".equalsIgnoreCase(el.tagName()) &&
                        ("stylesheet".equalsIgnoreCase(el.attr("rel")) || resUri.getPath() != null && resUri.getPath().toLowerCase().endsWith(".css"));

                Path resLocal = mapUriToLocalPath(outputDir, resUri, false);
                Files.createDirectories(resLocal.getParent());

                if (isStylesheet) {
                    // 读取 CSS 文本
                    org.jsoup.Connection.Response resp = Jsoup.connect(resUri.toString())
                            .ignoreContentType(true)
                            .timeout(20000)
                            .header("Referer", pageUri.toString())
                            .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36")
                            .ignoreHttpErrors(true)
                            .maxBodySize(0)
                            .execute();
                    String cssText = new String(resp.bodyAsBytes(), StandardCharsets.UTF_8);
                    String rewritten = rewriteCssUrls(cssText, resUri, outputDir, resLocal, result);
                    Files.write(resLocal, rewritten.getBytes(StandardCharsets.UTF_8));
                    result.setAssetsDownloaded(result.getAssetsDownloaded() + 1);
                } else {
                    if ("script".equalsIgnoreCase(el.tagName())) {
                        byte[] bytes = fetchBinary(resUri, pageUri);
                        String jsText = new String(bytes, StandardCharsets.UTF_8);
                        // 先重写 JS 内的跳转链接（外链→/，站内→相对路径并去掉 index.html）
                        String jsRewritten = rewriteJsLinksInContent(jsText, pageUri, outputDir, localHtmlPath, result);
                        // 保存 JS
                        Files.write(resLocal, jsRewritten.getBytes(StandardCharsets.UTF_8));
                        result.setAssetsDownloaded(result.getAssetsDownloaded() + 1);
                        // 在重写后的 JS 内容中提取并下载图片资源
                        try {
                            processJsForAssets(jsRewritten.getBytes(StandardCharsets.UTF_8), resUri, pageUri, outputDir, localHtmlPath, result);
                        } catch (Exception ex) {
                            result.addError("JS 资源提取失败: " + resUri + " -> " + ex.getMessage());
                        }
                    } else {
                        byte[] bytes = fetchBinary(resUri, pageUri);
                        Files.write(resLocal, bytes);
                        result.setAssetsDownloaded(result.getAssetsDownloaded() + 1);
                    }
                }

                String relative = computeRelativePath(localHtmlPath.getParent(), resLocal);
                el.attr(attr, relative);
            } catch (Exception ex) {
                result.addError("资源下载失败: " + resUri + " -> " + ex.getMessage());
            }
		}

        // 处理内联样式与 <style> 块中的背景图片
        processInlineStyles(doc, pageUri, outputDir, localHtmlPath, result);

        // 处理 img[srcset] 与懒加载属性
        for (Element img : doc.select("img")) {
            String srcset = img.attr("srcset");
            if (!isBlank(srcset)) {
                String rewritten = rewriteSrcSet(srcset, pageUri, outputDir, localHtmlPath, result);
                if (!isBlank(rewritten)) img.attr("srcset", rewritten);
            }
            String[] lazyAttrs = new String[]{"data-src", "data-original", "data-lazy", "data-echo"};
            for (String la : lazyAttrs) {
                String val = img.attr(la);
                if (isBlank(val)) continue;
                try {
                    URI abs = pageUri.resolve(val);
                    Path assetLocal = mapUriToLocalPath(outputDir, abs, false);
                    Files.createDirectories(assetLocal.getParent());
                    byte[] bytes = fetchBinary(abs, pageUri);
                    Files.write(assetLocal, bytes);
                    result.setAssetsDownloaded(result.getAssetsDownloaded() + 1);
                    String rel = computeRelativePath(localHtmlPath.getParent(), assetLocal);
                    img.attr(la, rel);
                    if (isBlank(img.attr("src"))) img.attr("src", rel);
                } catch (Exception ex) {
                    result.addError("懒加载图片下载失败: " + val + " -> " + ex.getMessage());
                }
            }
        }

        // 重写页面内链接 a[href]
        for (Element a : doc.select("a[href]")) {
            String href = a.attr("href");
            if (href == null || href.trim().isEmpty()) continue;
            if (href.startsWith("#")) continue; // 锚点
            URI target = safeUri(a.attr("abs:href"));
            if (target == null) continue;
            if (!isHttpLike(target)) continue; // 如 mailto:

            // 若为跨域链接，改为跳转首页（相对路径 /）
            String pageHost = pageUri.getHost();
            String targetHost = target.getHost();
            if (pageHost != null && targetHost != null && !targetHost.equalsIgnoreCase(pageHost)) {
                try {
                    a.attr("href", "/");
                } catch (Exception ex) {
                    result.addError("外链改首页失败: " + target + " -> " + ex.getMessage());
                }
                continue;
            }

            try {
                if (isLikelyHtml(target)) {
                    Path targetLocal = mapUriToLocalPath(outputDir, target, true);
                    Files.createDirectories(targetLocal.getParent());
                    String rel = computeRelativePath(localHtmlPath.getParent(), targetLocal);
                    // 去除 index.html 规范化
                    if (rel.endsWith("/index.html")) {
                        rel = rel.substring(0, rel.length() - "/index.html".length()) + "/";
                    } else if (rel.equals("index.html")) {
                        rel = "/";
                    }
                    a.attr("href", rel);
                } else {
                    // 非HTML: 当作静态资产下载并重写为相对路径
                    Path assetLocal = mapUriToLocalPath(outputDir, target, false);
                    Files.createDirectories(assetLocal.getParent());
                    byte[] bytes = fetchBinary(target, pageUri);
                    Files.write(assetLocal, bytes);
                    result.setAssetsDownloaded(result.getAssetsDownloaded() + 1);
                    String rel = computeRelativePath(localHtmlPath.getParent(), assetLocal);
                    a.attr("href", rel);
                }
            } catch (Exception ex) {
                result.addError("链接重写失败: " + target + " -> " + ex.getMessage());
            }
        }

        // 处理 <source src> 与 <source srcset>（用于 <picture>/<video>/<audio>）
        for (Element source : doc.select("source[src], source[srcset]")) {
            String src = source.attr("src");
            if (!isBlank(src)) {
                try {
                    URI abs = pageUri.resolve(src);
                    Path assetLocal = mapUriToLocalPath(outputDir, abs, false);
                    Files.createDirectories(assetLocal.getParent());
                    byte[] bytes = fetchBinary(abs, pageUri);
                    Files.write(assetLocal, bytes);
                    result.setAssetsDownloaded(result.getAssetsDownloaded() + 1);
                    String rel = computeRelativePath(localHtmlPath.getParent(), assetLocal);
                    source.attr("src", rel);
                } catch (Exception ex) {
                    result.addError("source 资源下载失败: " + src + " -> " + ex.getMessage());
                }
            }
            String srcset2 = source.attr("srcset");
            if (!isBlank(srcset2)) {
                String rewritten = rewriteSrcSet(srcset2, pageUri, outputDir, localHtmlPath, result);
                if (!isBlank(rewritten)) source.attr("srcset", rewritten);
            }
        }

        // 处理 <link rel=preload as=image href=...>
        for (Element link : doc.select("link[rel=preload][as=image][href]")) {
            String href = link.attr("href");
            if (isBlank(href)) continue;
            try {
                URI abs = pageUri.resolve(href);
                Path assetLocal = mapUriToLocalPath(outputDir, abs, false);
                Files.createDirectories(assetLocal.getParent());
                byte[] bytes = fetchBinary(abs, pageUri);
                Files.write(assetLocal, bytes);
                result.setAssetsDownloaded(result.getAssetsDownloaded() + 1);
                String rel = computeRelativePath(localHtmlPath.getParent(), assetLocal);
                link.attr("href", rel);
            } catch (Exception ex) {
                result.addError("preload 图片下载失败: " + href + " -> " + ex.getMessage());
            }
        }

        // 增强标题：原标题-后缀，并在 <body> 顶部插入 H1
        String originalTitle = doc.title();
        String suffix = request.getTitleSuffix();
        String newTitle;
        if (isBlank(suffix)) {
            newTitle = originalTitle; // 未填写则不追加
        } else {
            newTitle = (isBlank(originalTitle) ? "" : originalTitle) + (isBlank(originalTitle) ? suffix : ("-" + suffix));
        }
        if (doc.head() != null && !isBlank(newTitle)) {
            Element titleEl = doc.selectFirst("head > title");
            if (titleEl == null) {
                if (doc.head() != null) {
                    doc.head().appendElement("title").text(newTitle);
                }
            } else {
                titleEl.text(newTitle);
            }
        }
        if (doc.body() != null && !isBlank(newTitle)) {
            doc.body().insertChildren(0, org.jsoup.parser.Parser.parseFragment("<h1>" + org.jsoup.nodes.Entities.escape(newTitle) + "</h1>", doc.body(), doc.baseUri()));
        }

        // 统一将页面保存为 index.html 或 .html
        byte[] htmlBytes = doc.outerHtml().getBytes(StandardCharsets.UTF_8);
        Files.write(localHtmlPath, htmlBytes);
	}

    private void processInlineStyles(Document doc,
                                     URI pageUri,
                                     Path outputDir,
                                     Path localHtmlPath,
                                     CrawlResult result) {
        // style 属性
        for (Element el : doc.select("*[style]")) {
            String style = el.attr("style");
            if (isBlank(style)) continue;
            String rewritten = rewriteCssUrls(style, pageUri, outputDir, localHtmlPath, result);
            el.attr("style", rewritten);
        }
        // <style> 标签内容
        for (Element st : doc.select("style")) {
            String css = st.data();
            if (isBlank(css)) css = st.html();
            if (isBlank(css)) continue;
            String rewritten = rewriteCssUrls(css, pageUri, outputDir, localHtmlPath, result);
            st.text(rewritten);
        }
    }

    private String rewriteCssUrls(String cssText,
                                  URI baseUri,
                                  Path outputDir,
                                  Path currentLocalPath,
                                  CrawlResult result) {
        Matcher m = CSS_URL_PATTERN.matcher(cssText);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String rawUrl = m.group(2);
            String trimmed = rawUrl == null ? null : rawUrl.trim();
            if (isBlank(trimmed)) {
                m.appendReplacement(sb, m.group());
                continue;
            }
            String lower = trimmed.toLowerCase();
            if (lower.startsWith("data:")) {
                m.appendReplacement(sb, m.group());
                continue;
            }
            URI abs = null;
            try {
                abs = baseUri.resolve(trimmed);
            } catch (Exception ignore) {}
            if (abs == null || !isHttpLike(abs)) {
                m.appendReplacement(sb, m.group());
                continue;
            }
            try {
                Path assetLocal = mapUriToLocalPath(outputDir, abs, false);
                Files.createDirectories(assetLocal.getParent());
                byte[] bytes = fetchBinary(abs, baseUri);
                Files.write(assetLocal, bytes);
                result.setAssetsDownloaded(result.getAssetsDownloaded() + 1);
                String rel = computeRelativePath(currentLocalPath.getParent(), assetLocal);
                String replacement = "url('" + rel.replace("$", "\\$") + "')";
                m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
            } catch (Exception ex) {
                result.addError("CSS 资源下载失败: " + abs + " -> " + ex.getMessage());
                m.appendReplacement(sb, m.group());
            }
        }
        m.appendTail(sb);
        return sb.toString();
    }

    // 将 JS 字符串中的 URL 做清洗（去反斜杠、解转义）
    private static String sanitizeJsUrl(String s) {
        if (s == null) return null;
        String cleaned = s.trim();
        while (cleaned.endsWith("\\")) {
            cleaned = cleaned.substring(0, cleaned.length() - 1);
        }
        cleaned = cleaned.replace("\\/", "/")
                .replace("\\?", "?")
                .replace("\\&", "&")
                .replace("\\=", "=")
                .replace("\\#", "#");
        return cleaned;
    }

    public String rewriteJsLinksInContent(String jsText,
                                          URI pageUri,
                                          Path outputDir,
                                          Path currentLocalPath,
                                          CrawlResult result) {
        String text = jsText;

        int attrMatches = 0, attrRewrites = 0, attrSkips = 0;
        int locMatches = 0, locRewrites = 0, locSkips = 0;
        int openMatches = 0, openRewrites = 0, openSkips = 0;
        int escAttrMatches = 0, escAttrRewrites = 0, escAttrSkips = 0;
        int escLocMatches = 0, escLocRewrites = 0, escLocSkips = 0;
        int escOpenMatches = 0, escOpenRewrites = 0, escOpenSkips = 0;

        // 处理 HTML 片段中的 href/src 属性
        Matcher mAttr = HREF_SRC_ATTR_PATTERN.matcher(text);
        StringBuffer attrBuf = new StringBuffer();
        while (mAttr.find()) {
            String attr = mAttr.group(1);
            String quote = mAttr.group(2);
            String rawUrl = sanitizeJsUrl(mAttr.group(3));
            String replacement = mAttr.group();
            attrMatches++;
            try {
                URI abs = resolveAssetUri(rawUrl, pageUri, pageUri);
                String pageHost = pageUri.getHost();
                String targetHost = abs.getHost();
                String escapedQuote = quote.equals("\"") ? "\\\"" : "\\'";
                // 记录为候选页面
                if (isLikelyHtml(abs)) result.addJsPage(abs.toString());
                if (pageHost != null && targetHost != null && !targetHost.equalsIgnoreCase(pageHost)) {
                    replacement = attr + "=" + escapedQuote + "/" + escapedQuote;
                    attrRewrites++;
                } else {
                    Path targetLocal = mapUriToLocalPath(outputDir, abs, true);
                    Files.createDirectories(targetLocal.getParent());
                    String rel = computeRelativePath(currentLocalPath.getParent(), targetLocal);
                    if (rel.endsWith("/index.html")) {
                        rel = rel.substring(0, rel.length() - "/index.html".length()) + "/";
                    } else if (rel.equals("index.html")) {
                        rel = "/";
                    }
                    replacement = attr + "=" + escapedQuote + rel + escapedQuote;
                    attrRewrites++;
                }
            } catch (Exception ex) {
                attrSkips++;
            }
            mAttr.appendReplacement(attrBuf, Matcher.quoteReplacement(replacement));
        }
        mAttr.appendTail(attrBuf);
        text = attrBuf.toString();

        // 处理 location.href = '...'
        Matcher mLoc = JS_LOC_HREF_PATTERN.matcher(text);
        StringBuffer locBuf = new StringBuffer();
        while (mLoc.find()) {
            String quote = mLoc.group(1);
            String rawUrl = sanitizeJsUrl(mLoc.group(2));
            String replacement = mLoc.group();
            locMatches++;
            try {
                URI abs = resolveAssetUri(rawUrl, pageUri, pageUri);
                String pageHost = pageUri.getHost();
                String targetHost = abs.getHost();
                String escapedQuote = quote.equals("\"") ? "\\\"" : "\\'";
                if (isLikelyHtml(abs)) result.addJsPage(abs.toString());
                if (pageHost != null && targetHost != null && !targetHost.equalsIgnoreCase(pageHost)) {
                    replacement = "location.href=" + escapedQuote + "/" + escapedQuote;
                    locRewrites++;
                } else {
                    Path targetLocal = mapUriToLocalPath(outputDir, abs, true);
                    Files.createDirectories(targetLocal.getParent());
                    String rel = computeRelativePath(currentLocalPath.getParent(), targetLocal);
                    if (rel.endsWith("/index.html")) {
                        rel = rel.substring(0, rel.length() - "/index.html".length()) + "/";
                    } else if (rel.equals("index.html")) {
                        rel = "/";
                    }
                    replacement = "location.href=" + escapedQuote + rel + escapedQuote;
                    locRewrites++;
                }
            } catch (Exception ex) {
                locSkips++;
            }
            mLoc.appendReplacement(locBuf, Matcher.quoteReplacement(replacement));
        }
        mLoc.appendTail(locBuf);
        text = locBuf.toString();

        // 处理 window.open('...'
        Matcher mOpen = JS_WINDOW_OPEN_PATTERN.matcher(text);
        StringBuffer openBuf = new StringBuffer();
        while (mOpen.find()) {
            String quote = mOpen.group(1);
            String rawUrl = sanitizeJsUrl(mOpen.group(2));
            String replacement = mOpen.group();
            openMatches++;
            try {
                URI abs = resolveAssetUri(rawUrl, pageUri, pageUri);
                String pageHost = pageUri.getHost();
                String targetHost = abs.getHost();
                String escapedQuote = quote.equals("\"") ? "\\\"" : "\\'";
                if (isLikelyHtml(abs)) result.addJsPage(abs.toString());
                if (pageHost != null && targetHost != null && !targetHost.equalsIgnoreCase(pageHost)) {
                    replacement = "window.open(" + escapedQuote + "/" + escapedQuote;
                    openRewrites++;
                } else {
                    Path targetLocal = mapUriToLocalPath(outputDir, abs, true);
                    Files.createDirectories(targetLocal.getParent());
                    String rel = computeRelativePath(currentLocalPath.getParent(), targetLocal);
                    if (rel.endsWith("/index.html")) {
                        rel = rel.substring(0, rel.length() - "/index.html".length()) + "/";
                    } else if (rel.equals("index.html")) {
                        rel = "/";
                    }
                    replacement = "window.open(" + escapedQuote + rel + escapedQuote;
                    openRewrites++;
                }
            } catch (Exception ex) {
                openSkips++;
            }
            mOpen.appendReplacement(openBuf, Matcher.quoteReplacement(replacement));
        }
        mOpen.appendTail(openBuf);
        text = openBuf.toString();

        // 简化替换（最后兜底）
        int simpleCount = 0;
        String before;
        before = text;
        text = text.replaceAll("(?i)href=\"https?://[^\"]+\"", "href=\"/\"");
        text = text.replaceAll("(?i)href='https?://[^']+'", "href='/'");
        text = text.replaceAll("(?i)src=\"https?://[^\"]+\"", "src=\"/\"");
        text = text.replaceAll("(?i)src='https?://[^']+'", "src='/'");
        if (!text.equals(before)) simpleCount++;
        before = text;
        text = text.replaceAll("(?i)href=\\\\\"https?://[^\\\\\"]+\\\\\"", "href=\\\\\"/\\\\\"");
        text = text.replaceAll("(?i)href=\\\\'https?://[^\\\\']+\\\\'", "href=\\\\'/\\\\'");
        text = text.replaceAll("(?i)src=\\\\\"https?://[^\\\\\"]+\\\\\"", "src=\\\\\"/\\\\\"");
        text = text.replaceAll("(?i)src=\\\\'https?://[^\\\\']+\\\\'", "src=\\\\'/\\\\'");
        if (!text.equals(before)) simpleCount++;
        before = text;
        text = text.replaceAll("(?i)location\\.href\\s*=\\s*\"https?://[^\"]+\"", "location.href=\"/\"");
        text = text.replaceAll("(?i)location\\.href\\s*=\\s*'https?://[^']+'", "location.href='/'");
        text = text.replaceAll("(?i)window\\.open\\(\\s*\"https?://[^\"]+\"", "window.open(\"/\"");
        text = text.replaceAll("(?i)window\\.open\\(\\s*'https?://[^']+'", "window.open('/'");
        if (!text.equals(before)) simpleCount++;
        before = text;
        text = text.replaceAll("(?i)location\\.href\\s*=\\s*\\\\\"https?://[^\\\\\"]+\\\\\"", "location.href=\\\\\"/\\\\\"");
        text = text.replaceAll("(?i)location\\.href\\s*=\\s*\\\\'https?://[^\\\\']+\\\\'", "location.href=\\\\'/\\\\'");
        text = text.replaceAll("(?i)window\\.open\\(\\s*\\\\\"https?://[^\\\\\"]+\\\\\"", "window.open(\\\\\"/\\\\\"");
        text = text.replaceAll("(?i)window\\.open\\(\\s*\\\\'https?://[^\\\\']+\\\\'", "window.open(\\\\'/\\\\'");
        if (!text.equals(before)) simpleCount++;
        before = text;
        text = text.replaceAll("(?i)href=\"(/[^\"]*?)/index\\.html\"", "href=\"$1/\"");
        text = text.replaceAll("(?i)href='(/[^']*?)/index\\.html'", "href='$1/'");
        text = text.replaceAll("(?i)src=\"(/[^\"]*?)/index\\.html\"", "src=\"$1/\"");
        text = text.replaceAll("(?i)src='(/[^']*?)/index\\.html'", "src='$1/'");
        if (!text.equals(before)) simpleCount++;
        before = text;
        text = text.replaceAll("(?i)href=\\\\\"(/[^\\\\\"]*?)/index\\.html\\\\\"", "href=\\\\\"$1/\\\\\"");
        text = text.replaceAll("(?i)href=\\\\'(/[^\\\\']*?)/index\\.html\\\\'", "href=\\\\'$1/\\\\'");
        text = text.replaceAll("(?i)src=\\\\\"(/[^\\\\\"]*?)/index\\.html\\\\\"", "src=\\\\\"$1/\\\\\"");
        text = text.replaceAll("(?i)src=\\\\'(/[^\\\\']*?)/index\\.html\\\\'", "src=\\\\'$1/\\\\'");
        System.out.println("[JS-SIMPLE][SUMMARY] passesApplied=" + simpleCount);

        // 简单页面 URL 收集：/col/.../index.html 或 /col/.../ 视为页面
        collectJsPages(text, pageUri, result);

        return text;
    }

    // 收集 JS 文本中潜在页面 URL（用于加入下载）
    private void collectJsPages(String text, URI pageUri, CrawlResult result) {
        Pattern P1 = Pattern.compile("(['\"])(/[^'\"\\\s<>]+/index\\.html)\\1", Pattern.CASE_INSENSITIVE);
        Pattern P2 = Pattern.compile("(['\"])(/[^'\"\\\s<>]+/)\\1", Pattern.CASE_INSENSITIVE);
        Matcher p1 = P1.matcher(text);
        while (p1.find()) {
            try { result.addJsPage(pageUri.resolve(p1.group(2)).toString()); } catch (Exception ignore) {}
        }
        Matcher p2 = P2.matcher(text);
        while (p2.find()) {
            try { result.addJsPage(pageUri.resolve(p2.group(2)).toString()); } catch (Exception ignore) {}
        }
    }

    private void processJsForAssets(byte[] jsBytes,
                                    URI jsUri,
                                    URI referer,
                                    Path outputDir,
                                    Path currentLocalPath,
                                    CrawlResult result) throws IOException {
        String js = new String(jsBytes, StandardCharsets.UTF_8);
        java.util.Set<String> seen = new java.util.HashSet<String>();
        // 1) 提取 url(...)，仅下载图片
        Matcher m1 = CSS_URL_PATTERN.matcher(js);
        while (m1.find()) {
            String raw = m1.group(2);
            if (isBlank(raw)) continue;
            if (!isImagePath(raw)) continue;
            if (!seen.add(raw)) continue;
            downloadOneAssetFromJs(raw, jsUri, referer, outputDir, currentLocalPath, result, "JS-CSSURL");
        }
        // 2) 提取引号内的图片路径
        Matcher m2 = QUOTED_ASSET_PATTERN.matcher(js);
        while (m2.find()) {
            String raw = m2.group(1);
            if (isBlank(raw)) continue;
            if (!seen.add(raw)) continue;
            downloadOneAssetFromJs(raw, jsUri, referer, outputDir, currentLocalPath, result, "JS-QUOTED");
        }
        // 3) 宽松匹配图片后缀 token
        Matcher m3 = JS_IMG_TOKEN_PATTERN.matcher(js);
        while (m3.find()) {
            String raw = m3.group(1);
            if (isBlank(raw)) continue;
            if (!seen.add(raw)) continue;
            downloadOneAssetFromJs(raw, jsUri, referer, outputDir, currentLocalPath, result, "JS-TOKEN");
        }
    }

    private void downloadOneAssetFromJs(String raw,
                                        URI base,
                                        URI referer,
                                        Path outputDir,
                                        Path currentLocalPath,
                                        CrawlResult result,
                                        String tag) {
        try {
            String cleaned = sanitizeJsUrl(raw);
            if (isBlank(cleaned)) return;
            if (!isImagePath(cleaned)) return; // 仅下载图片
            URI abs = resolveAssetUri(cleaned, base, referer);
            if (!isHttpLike(abs)) return;
            Path assetLocal = mapUriToLocalPath(outputDir, abs, false);
            Files.createDirectories(assetLocal.getParent());
            byte[] bytes = fetchBinary(abs, referer);
            Files.write(assetLocal, bytes);
            result.setAssetsDownloaded(result.getAssetsDownloaded() + 1);
        } catch (Exception ex) {
            result.addError("JS 引用资源下载失败: " + raw + " -> " + ex.getMessage());
        }
    }

    private static boolean isImagePath(String s) {
        if (s == null) return false;
        String lower = s.toLowerCase();
        return lower.contains(".") && (lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg") ||
                lower.endsWith(".gif") || lower.endsWith(".webp") || lower.endsWith(".svg") || lower.endsWith(".ico"));
    }

    private byte[] fetchBinary(URI url, URI referer) throws IOException {
        int attempts = 0;
        IOException last = null;
        while (attempts < 3) {
            attempts++;
            try {
                return Jsoup.connect(url.toString())
                        .ignoreContentType(true)
                        .timeout(30000)
                        .header("Referer", referer == null ? url.toString() : referer.toString())
                        .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36")
                        .ignoreHttpErrors(true)
                        .maxBodySize(0)
                        .execute()
                        .bodyAsBytes();
            } catch (IOException ex) {
                last = ex;
                try { Thread.sleep(500L * attempts); } catch (InterruptedException ignored) {}
            }
        }
        throw last == null ? new IOException("Unknown download error") : last;
    }

    private static URI resolveAssetUri(String cleaned, URI base, URI referer) throws URISyntaxException {
        if (cleaned.startsWith("http://") || cleaned.startsWith("https://")) {
            return new URI(cleaned);
        }
        if (cleaned.startsWith("//")) {
            String scheme = base != null && base.getScheme() != null ? base.getScheme() : (referer != null && referer.getScheme() != null ? referer.getScheme() : "https");
            return new URI(scheme + ":" + cleaned);
        }
        URI from = base != null ? base : referer;
        URI abs = from != null ? from.resolve(cleaned) : null;
        if (abs != null && abs.getHost() != null) return abs;
        // 拼接当前域名
        String host = referer != null ? referer.getHost() : (base != null ? base.getHost() : null);
        String scheme = referer != null && referer.getScheme() != null ? referer.getScheme() : (base != null && base.getScheme() != null ? base.getScheme() : "https");
        if (host != null) {
            String path = cleaned.startsWith("/") ? cleaned : "/" + cleaned;
            return new URI(scheme + "://" + host + path);
        }
        // 最后兜底：当作 https 绝对路径
        String path = cleaned.startsWith("/") ? cleaned : "/" + cleaned;
        return new URI("https://" + path.substring(1));
    }

    private String rewriteSrcSet(String srcset,
                                 URI baseUri,
                                 Path outputDir,
                                 Path currentLocalPath,
                                 CrawlResult result) {
        String[] parts = srcset.split(",");
        StringBuilder rebuilt = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            String item = parts[i].trim();
            if (isBlank(item)) continue;
            String urlPart = item;
            String descriptor = "";
            int sp = item.lastIndexOf(' ');
            if (sp > 0 && sp < item.length() - 1) {
                urlPart = item.substring(0, sp).trim();
                descriptor = item.substring(sp + 1).trim();
            }
            try {
                URI abs = baseUri.resolve(urlPart);
                Path assetLocal = mapUriToLocalPath(outputDir, abs, false);
                Files.createDirectories(assetLocal.getParent());
                byte[] bytes = fetchBinary(abs, baseUri);
                Files.write(assetLocal, bytes);
                result.setAssetsDownloaded(result.getAssetsDownloaded() + 1);
                String rel = computeRelativePath(currentLocalPath.getParent(), assetLocal);
                if (rebuilt.length() > 0) rebuilt.append(", ");
                rebuilt.append(rel);
                if (!isBlank(descriptor)) rebuilt.append(' ').append(descriptor);
            } catch (Exception ex) {
                result.addError("srcset 下载失败: " + item + " -> " + ex.getMessage());
            }
        }
        return rebuilt.toString();
    }

	private static URI normalizeUri(String url) throws URISyntaxException {
		URI uri = new URI(url);
		if (uri.getScheme() == null) {
			uri = new URI("https://" + url);
		}
		return uri.normalize();
	}

	private static URI safeUri(String url) {
		try {
			return normalizeUri(url);
		} catch (Exception e) {
			return null;
		}
	}

    private static String sanitizeFileName(String name) {
        try {
            String decoded = URLDecoder.decode(name, "UTF-8");
            return decoded.replaceAll("[^a-zA-Z0-9._-]", "-");
        } catch (UnsupportedEncodingException e) {
            return name.replaceAll("[^a-zA-Z0-9._-]", "-");
        }
    }

    private static String[] sanitizePathSegments(String[] segments) {
        String[] sanitized = new String[segments.length];
        for (int i = 0; i < segments.length; i++) {
            String s = segments[i];
            if (s == null || s.trim().isEmpty()) {
                sanitized[i] = "";
            } else {
                sanitized[i] = sanitizeFileName(s);
            }
        }
        return sanitized;
    }

    private static Path mapUriToLocalPath(Path outputRoot, URI uri, boolean isHtml) {
        String host = uri.getHost() == null ? "unknown-host" : uri.getHost();
        String rawPath = uri.getPath();
        if (rawPath == null || rawPath.trim().isEmpty() || "/".equals(rawPath)) {
            rawPath = "/"; // 根路径
        }

        // 分段并清洗
        String[] parts = Arrays.stream(rawPath.split("/", -1)).toArray(String[]::new);
        parts = sanitizePathSegments(parts);

        // 处理文件名与扩展
        if (isHtml) {
            if (rawPath.endsWith("/")) {
                // 目录 → index.html
                parts = Arrays.copyOf(parts, parts.length + 1);
                parts[parts.length - 1] = "index.html";
            } else {
                String last = parts.length == 0 ? "index.html" : parts[parts.length - 1];
                if (!last.contains(".")) {
                    parts[parts.length - 1] = last + ".html";
                }
            }
        }

        // 附加查询字符串影响
        String query = uri.getQuery();
        if (query != null && !query.trim().isEmpty()) {
            String suffix = "_q_" + sanitizeFileName(query);
            int lastIndex = parts.length - 1;
            if (lastIndex >= 0 && parts[lastIndex] != null && !parts[lastIndex].trim().isEmpty()) {
                String last = parts[lastIndex];
                int dot = last.lastIndexOf('.')
;                if (dot > 0) {
                    parts[lastIndex] = last.substring(0, dot) + suffix + last.substring(dot);
                } else {
                    parts[lastIndex] = last + suffix + (isHtml ? ".html" : "");
                }
            } else {
                parts = Arrays.copyOf(parts, parts.length + 1);
                parts[parts.length - 1] = (isHtml ? "index" : "index") + suffix + (isHtml ? ".html" : "");
            }
        }

        Path path = Paths.get(host);
        for (String p : parts) {
            if (p == null || p.trim().isEmpty()) continue;
            path = path.resolve(p);
        }
        return outputRoot.resolve(path);
    }

	private static String computeRelativePath(Path fromDir, Path target) {
		Path rel = fromDir.relativize(target);
		String s = rel.toString();
		return s.replace('\\', '/');
	}

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    private static boolean isHttpLike(URI uri) {
        String scheme = uri.getScheme();
        if (scheme == null) return false;
        String s = scheme.toLowerCase();
        return s.equals("http") || s.equals("https");
    }

    private static boolean isLikelyHtml(URI uri) {
        // 根据扩展名进行粗略判断：没有扩展名或常见 HTML 结尾认为是页面
        String path = uri.getPath();
        if (isBlank(path)) return true;
        String lower = path.toLowerCase();
        if (lower.endsWith(".html") || lower.endsWith(".htm") || lower.endsWith(".shtml") || lower.endsWith(".xhtml")) return true;
        // 常见静态资源扩展，认为不是 HTML
        String[] nonHtmlExt = new String[]{
                ".jpg", ".jpeg", ".png", ".gif", ".webp", ".svg", ".ico",
                ".css", ".js", ".json", ".map",
                ".pdf", ".doc", ".docx", ".xls", ".xlsx", ".ppt", ".pptx",
                ".zip", ".rar", ".7z", ".gz", ".tar"
        };
        for (String ext : nonHtmlExt) {
            if (lower.endsWith(ext)) return false;
        }
        // 无明确扩展：视为 HTML，交给 Jsoup/请求时再判断
        return true;
    }
}


