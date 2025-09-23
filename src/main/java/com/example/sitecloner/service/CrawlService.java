package com.example.sitecloner.service;

import com.example.sitecloner.model.CrawlRequest;
import com.example.sitecloner.model.CrawlResult;
import com.example.sitecloner.config.StorageProperties;
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
import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class CrawlService {

    private final StorageProperties storageProperties;

    public CrawlService(StorageProperties storageProperties) {
        this.storageProperties = storageProperties;
    }

    // 正则：匹配 CSS/JS 文本中的 url(...) 模式
    private static final Pattern CSS_URL_PATTERN = Pattern.compile("url\\(\\s*(['\\\"]?)([^\\)\\'\\\"]+)\\1\\s*\\)", Pattern.CASE_INSENSITIVE);
    // 正则：仅匹配 JS 文本中被引号包裹的图片 URL（支持 http(s)、/、\\/、./、../、以及简易相对路径）
    private static final Pattern QUOTED_ASSET_PATTERN = Pattern.compile("['\\\"]((?:https?://|/|\\\\/|\\./|\\.\\./|[a-zA-Z0-9_./-])[^'\\\"]+\\.(?:png|jpe?g|gif|webp|svg|ico))(?:\\?[^'\\\"]*)?['\\\"]", Pattern.CASE_INSENSITIVE);
    // 正则：宽松匹配 JS 文本中未必被引号包裹的图片 URL 片段（仅限以 http(s)、/、\\/、./、../ 开头，避免误捕获 CSS 片段），并避免从路径中间起始
    private static final Pattern JS_IMG_TOKEN_PATTERN = Pattern.compile("(?<![A-Za-z0-9_./-])((?:https?://|/|\\\\/|\\./|\\.\\./)[^\\s'\\\"<>]+\\.(?:png|jpe?g|gif|webp|svg|ico))(?:\\?[^\\s'\\\"<>]*)?", Pattern.CASE_INSENSITIVE);
    // 正则：匹配 JS 源码中以转义双引号包裹的图片 URL，如 src=\"/a.png\" 或 \"\/a.png\"
    private static final Pattern ESC_DQ_IMG_PATTERN = Pattern.compile("\\\\\"((?:https?://|/|\\\\/|\\./|\\.\\./)[^\\\\\"\\s<>]+\\.(?:png|jpe?g|gif|webp|svg|ico))(?:\\?[^\\\\\"\\s<>]*)?\\\\\"", Pattern.CASE_INSENSITIVE);
    // 正则：匹配 JS 源码中以转义单引号包裹的图片 URL，如 src=\'/a.png\' 或 \'\/a.png\'
    private static final Pattern ESC_SQ_IMG_PATTERN = Pattern.compile("\\\\'((?:https?://|/|\\\\/|\\./|\\.\\./)[^\\\\'\\s<>]+\\.(?:png|jpe?g|gif|webp|svg|ico))(?:\\?[^\\\\'\\s<>]*)?\\\\'", Pattern.CASE_INSENSITIVE);
    // 正则：定位 JS 中的 location.href = '...'
    private static final Pattern JS_LOC_HREF_PATTERN = Pattern.compile("(?:window\\.)?location\\.href\\s*=\\s*(['\\\"])((?:https?://|/)[^'\\\"\\s<>]+)\\1", Pattern.CASE_INSENSITIVE);
    // 正则：定位 JS 中的 window.open('...'
    private static final Pattern JS_WINDOW_OPEN_PATTERN = Pattern.compile("window\\.open\\(\\s*(['\\\"])((?:https?://|/)[^'\\\"\\s<>]+)\\1", Pattern.CASE_INSENSITIVE);
    // 正则：定位 JS 字符串中的 href=\"...\"、src=\"...\"、action=\"...\"（用于 HTML 片段，非转义）
    private static final Pattern ATTR_NONESC_PATTERN = Pattern.compile("(href|src|action)\\s*=\\s*(['\\\"])((?:https?://|/)[^'\\\"\\s<>]+)\\2", Pattern.CASE_INSENSITIVE);
    // 正则：定位 JS 字符串中的 href=\\\"...\\\"、src=\\\"...\\\"、action=\\\"...\\\"（转义引号）
    private static final Pattern ATTR_ESC_PATTERN = Pattern.compile("(href|src|action)\\s*=\\s*\\\\(?:[\\\\\"'])((?:https?://|/)[^\\\\'\\\"\\s<>]+)(?:\\\\\\\"|\\\\')", Pattern.CASE_INSENSITIVE);
    // 正则：直接匹配 <img ... src="...png">（非转义）
    private static final Pattern IMG_TAG_SRC_NONESC = Pattern.compile("<img[^>]+src\\s*=\\s*(['\\\"])([^'\\\"\\s<>]+\\.(?:png|jpe?g|gif|webp|svg|ico))(?:\\?[^'\\\"<>]*)?\\1", Pattern.CASE_INSENSITIVE);
    // 正则：匹配 JS 字符串中的转义形式 <img ... src=\"...png\">
    private static final Pattern IMG_TAG_SRC_ESC = Pattern.compile("<img[^>]+src\\s*=\\s*\\\\(['\\\"])([^\\\\'\\\"\\s<>]+\\.(?:png|jpe?g|gif|webp|svg|ico))(?:\\?[^\\\\'\\\"<>]*)?\\\\\\1", Pattern.CASE_INSENSITIVE);
    // 正则：匹配 JS 字符串中非转义的 <link ... href="...css">
    private static final Pattern LINK_TAG_HREF_NONESC = Pattern.compile("<link[^>]+href\\s*=\\s*(['\\\"])([^'\\\"\\s<>]+\\.css)(?:\\?[^'\\\"<>]*)?\\1", Pattern.CASE_INSENSITIVE);
    // 正则：匹配 JS 字符串中转义的 <link ... href=\"...css\">
    private static final Pattern LINK_TAG_HREF_ESC = Pattern.compile("<link[^>]+href\\s*=\\s*\\\\(['\\\"])([^\\\\'\\\"\\s<>]+\\.css)(?:\\?[^\\\\'\\\"<>]*)?\\\\\\1", Pattern.CASE_INSENSITIVE);
    // 正则：匹配 JS 字符串中非转义的 <script ... src="...js">
    private static final Pattern SCRIPT_TAG_SRC_NONESC = Pattern.compile("<script[^>]+src\\s*=\\s*(['\\\"])([^'\\\"\\s<>]+\\.js)(?:\\?[^'\\\"<>]*)?\\1", Pattern.CASE_INSENSITIVE);
    // 正则：匹配 JS 字符串中转义的 <script ... src=\"...js\">
    private static final Pattern SCRIPT_TAG_SRC_ESC = Pattern.compile("<script[^>]+src\\s*=\\s*\\\\(['\\\"])([^\\\\'\\\"\\s<>]+\\.js)(?:\\?[^\\\\'\\\"<>]*)?\\\\\\1", Pattern.CASE_INSENSITIVE);
    // 拆分拼接检测（document.write 常见）：href/src 属性值被 ' + " 分割
    private static final Pattern HREF_CSS_SPLIT1 = Pattern.compile("href\\s*=\\s*\"([^\"]*\\.css)\\s*'\\s*\\+", Pattern.CASE_INSENSITIVE);
    private static final Pattern HREF_CSS_SPLIT2 = Pattern.compile("href\\s*=\\s*'([^']*\\.css)\\s*\"\\s*\\+", Pattern.CASE_INSENSITIVE);
    private static final Pattern SRC_JS_SPLIT1  = Pattern.compile("src\\s*=\\s*\"([^\"]*\\.js)\\s*'\\s*\\+", Pattern.CASE_INSENSITIVE);
    private static final Pattern SRC_JS_SPLIT2  = Pattern.compile("src\\s*=\\s*'([^']*\\.js)\\s*\"\\s*\\+", Pattern.CASE_INSENSITIVE);

    public CrawlResult crawl(CrawlRequest request) {
        Instant start = Instant.now();
        CrawlResult result = new CrawlResult();
        try {
            URI startUri = normalizeUri(request.getStartUrl());
            String baseHost = startUri.getHost();
            String outputDirName = (!isBlank(request.getOutputName()))
                    ? request.getOutputName()
                    : sanitizeFileName(baseHost);
            Path baseDir = Paths.get(sanitizePathConfig(storageProperties.getOutputBaseDir()));
            Path outputDir = baseDir.resolve(outputDirName);
            Files.createDirectories(outputDir);

            breadthFirstCrawl(startUri, baseHost, request, outputDir, result);

            // 生成 sitemap.xml（放在站点根：outputDir/<host>/sitemap.xml）
            try {
                generateSitemap(outputDir, baseHost, request, result);
            } catch (Exception e) {
                result.addError("生成 sitemap.xml 失败: " + e.getMessage());
            }

            result.setOutputDirectory(outputDir.toAbsolutePath().toString());
        } catch (Exception e) {
            result.addError(e.getMessage());
        } finally {
            result.setElapsed(Duration.between(start, Instant.now()));
        }
        return result;
    }

    // 清洗外部配置的路径值（去掉首尾引号，去空白）
    private static String sanitizePathConfig(String raw) {
        if (raw == null) return "output";
        String v = raw.trim();
        if ((v.startsWith("\"") && v.endsWith("\"")) || (v.startsWith("'") && v.endsWith("'"))) {
            v = v.substring(1, v.length() - 1).trim();
        }
        return v;
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
        System.out.println("[BFS][START] maxDepth=" + request.getMaxDepth() + ", maxPages=" + request.getMaxPages());

        while (!queue.isEmpty() && pages < request.getMaxPages() && depth <= request.getMaxDepth()) {
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
            System.out.println("[BFS][VISIT] depth=" + depth + " -> " + uri);

            try {
                org.jsoup.Connection.Response res = Jsoup.connect(uri.toString())
                        .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36")
                        .timeout(20000)
                        .ignoreHttpErrors(true)
                        .execute();
                int status = res.statusCode();
                if (status == 404) {
                    System.out.println("[PAGE][SKIP-404] " + uri);
                } else {
                    Document doc = res.parse();
                    Path localHtmlPath = mapUriToLocalPath(outputDir, uri, true);
                    Files.createDirectories(localHtmlPath.getParent());
                    rewriteAndSaveHtml(doc, uri, outputDir, localHtmlPath, request, result);
                    result.addPage(uri.toString());
                    pages++;
                    result.setPagesDownloaded(pages);

                    if (request.isDebugOnlyHome()) {
                        break;
                    }

                    // 从页面 a[href] 继续发现链接
                    Elements links = doc.select("a[href]");
                    for (Element a : links) {
                        String href = a.attr("abs:href");
                        if (href == null || href.trim().isEmpty()) continue;
                        URI next = safeUri(href);
                        if (next == null) continue;
                        if (isSitemapXml(next)) { System.out.println("[BFS][SKIP-SITEMAP] " + next); continue; }
                        if (request.isSameDomain() && !Objects.equals(next.getHost(), baseHost)) { System.out.println("[BFS][SKIP-XDOMAIN] " + next); continue; }
                        if (!isLikelyHtml(next)) { System.out.println("[BFS][SKIP-NONHTML] " + next); continue; }
                        if (visited.contains(next.toString())) { System.out.println("[BFS][SKIP-VISITED] " + next); continue; }
                        queue.add(next);
                        nextLevelCount++;
                        System.out.println("[BFS][ENQUEUE] depthNext=" + (depth+1) + " -> " + next);
                    }

                    // 将 JS 中收集到的页面加入队列（同域且未访问）
                    if (!result.getJsPages().isEmpty()) {
                        for (String pg : new java.util.HashSet<String>(result.getJsPages())) {
                            try {
                                URI next = safeUri(pg);
                                if (next == null) continue;
                                if (request.isSameDomain() && !Objects.equals(next.getHost(), baseHost)) continue;
                                if (!isLikelyHtml(next)) continue;
                                if (visited.contains(next.toString())) continue;
                                queue.add(next);
                                nextLevelCount++;
                            } catch (Exception ignore) {}
                        }
                        result.getJsPages().clear();
                    }
                }

            } catch (Exception ex) {
                result.addError(uri + " -> " + ex.getMessage());
            }

            if (currentLevelCount == 0) {
                depth++;
                currentLevelCount = nextLevelCount;
                nextLevelCount = 0;
                System.out.println("[BFS][LEVEL-END] depth=" + depth + ", queueSize=" + queue.size());
            }
        }
        System.out.println("[BFS][END] pages=" + pages + ", finalDepth=" + depth + ", remainingQueue=" + queue.size());
    }

    private void rewriteAndSaveHtml(Document doc,
                                    URI pageUri,
                                    Path outputDir,
                                    Path localHtmlPath,
                                    CrawlRequest request,
                                    CrawlResult result) throws IOException {
        // 先确保站点根资源（favicon、templets 下的 js）已准备好，防止后续下载同名资源覆盖
        try { ensureSiteAssets(outputDir, pageUri); } catch (Exception e) { result.addError("站点资产准备失败: " + e.getMessage()); }
        // 处理常见资源: img[src], script[src], link[href]
        for (Element el : doc.select("img[src], script[src], link[href]")) {
            String attr = el.hasAttr("src") ? "src" : "href";
            String abs = el.attr("abs:" + attr);
            if (abs == null || abs.trim().isEmpty()) continue;
            URI resUri = safeUri(abs);
            if (resUri == null) continue;
            if (isSitemapXml(resUri)) { System.out.println("[ASSET][SKIP-SITEMAP] " + resUri); continue; }
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
                    rewritten = applyReplacements(rewritten, request);
                    Files.write(resLocal, rewritten.getBytes(StandardCharsets.UTF_8));
                    result.setAssetsDownloaded(result.getAssetsDownloaded() + 1);
                } else {
                    if ("script".equalsIgnoreCase(el.tagName())) {
                        if (isProtectedSiteAsset(outputDir, resUri)) {
                            System.out.println("[ASSET][SKIP-PROTECTED][SCRIPT] " + resUri);
                            continue;
                        }
                        byte[] bytes = fetchBinary(resUri, pageUri);
                        String jsText = new String(bytes, StandardCharsets.UTF_8);
                        // 先重写 JS 内的跳转链接（外链→/，站内→相对路径并去掉 index.html）
                        String jsRewritten = rewriteJsLinksInContent(jsText, pageUri, outputDir, localHtmlPath, result);
                        jsRewritten = applyReplacements(jsRewritten, request);
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
                        String key = resUri.toString();
                        if (isProtectedSiteAsset(outputDir, resUri)) {
                            System.out.println("[ASSET][SKIP-PROTECTED] " + resUri);
                            continue;
                        }
                        if (!result.tryMarkAsset(key)) {
                            System.out.println("[ASSET][SKIP-DUP] " + key);
                        } else {
                            byte[] bytes = fetchBinary(resUri, pageUri);
                            Files.write(resLocal, bytes);
                            result.setAssetsDownloaded(result.getAssetsDownloaded() + 1);
                        }
                    }
                }

                String relative = computeRelativePath(localHtmlPath.getParent(), resLocal);
                el.attr(attr, relative);
            } catch (Exception ex) {
                result.addError("资源下载失败: " + resUri + " -> " + ex.getMessage());
            }
        }

        // 处理内联样式与 <style> 块中的背景图片（并应用替换）
        processInlineStyles(doc, pageUri, outputDir, localHtmlPath, result, request);

        // 确保站点根资产（favicon 与 templets 下的 js）已就绪，并在页面中引用
        try {
            ensureSiteAssets(outputDir, pageUri);
            addOrReplaceFavicon(doc);
            ensureHeadScript(doc, "/templets/gtt.js");
            if (isHomePage(pageUri)) ensureHeadScript(doc, "/templets/gg.js");
        } catch (Exception e) {
            result.addError("站点资产插入失败: " + e.getMessage());
        }

        // 处理页面内联 <script>（无 src）：重写其中的链接并收集页面、提取图片
        try {
            for (Element sc : doc.select("script:not([src])")) {
                String js = sc.data();
                if (isBlank(js)) js = sc.html();
                if (isBlank(js)) continue;
                String rewrittenJs = rewriteJsLinksInContent(js, pageUri, outputDir, localHtmlPath, result);
                // HTML 内联脚本专用：将转义引号形式还原为原始引号，例如 \"/path\" -> "/path"
                String htmlSafeJs = htmlInlineJsUnescapeQuotes(rewrittenJs);
                sc.text(htmlSafeJs);
                try {
                    processJsForAssets(htmlSafeJs.getBytes(StandardCharsets.UTF_8), pageUri, pageUri, outputDir, localHtmlPath, result);
                } catch (Exception ignore) {}
            }
        } catch (Exception ignore) {}

        // 处理常见行内事件/URL 属性中的 JS 片段（点击跳转等），既收集页面也回写重写后的内容
        try {
            String[] jsAttrs = new String[]{"onclick", "onmouseover", "onfocus", "onsubmit", "onload", "onchange"};
            String[] urlLikeAttrs = new String[]{"data-href", "data-url", "data-link"};
            for (Element el : doc.getAllElements()) {
                for (String a : jsAttrs) {
                    if (!el.hasAttr(a)) continue;
                    String v = el.attr(a);
                    if (isBlank(v)) continue;
                    String rewritten = rewriteJsLinksInContent(v, pageUri, outputDir, localHtmlPath, result);
                    if (!isBlank(rewritten) && !rewritten.equals(v)) el.attr(a, rewritten);
                }
                for (String a : urlLikeAttrs) {
                    if (!el.hasAttr(a)) continue;
                    String v = el.attr(a);
                    if (isBlank(v)) continue;
                    String rewritten = rewriteJsLinksInContent(v, pageUri, outputDir, localHtmlPath, result);
                    if (!isBlank(rewritten) && !rewritten.equals(v)) el.attr(a, rewritten);
                }
            }
        } catch (Exception ignore) {}

        // 移除 SEO 相关标签（更稳健的白/黑名单+大小写兼容）：
        // - 策略改为：仅保留白名单 meta（大小写不敏感、支持部分前缀），其它一律移除；不移除任何 <link>
        try {
            int removed = 0;
            String[] keepNames = new String[]{
                    "district", "viewport", "format-detection", "theme-color",
                    "renderer", "referrer", "apple-mobile-web-app-capable",
                    "apple-mobile-web-app-status-bar-style", "description"
            };
            for (Element meta : doc.select("head meta")) {
                if (meta.hasAttr("http-equiv") || meta.hasAttr("charset")) continue;
                String name = meta.attr("name");
                String property = meta.attr("property");
                boolean keep = false;
                if (name != null && !name.trim().isEmpty()) {
                    String ln = name.trim().toLowerCase();
                    if (ln.startsWith("msapplication-")) {
                        keep = true;
                    } else {
                        for (String k : keepNames) { if (ln.equals(k)) { keep = true; break; } }
                    }
                }
                // 若未命中白名单：
                if (!keep) {
                    // 带 property（如 og:/twitter:/article:）统一认为非必要，删除
                    if (property != null && !property.trim().isEmpty()) { meta.remove(); removed++; continue; }
                    // 有 name 但不在白名单，删除
                    if (name != null && !name.trim().isEmpty()) { meta.remove(); removed++; continue; }
                    // 既无 name/property 且非 http-equiv/charset，稳妥删除
                    meta.remove(); removed++;
                }
            }
            if (removed > 0) {
                System.out.println("[SEO][META][REMOVED-WHITELIST] " + removed + " from " + pageUri);
            }
        } catch (Exception ignore) {}

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
                    String key = abs.toString();
                    if (!result.tryMarkAsset(key)) {
                        System.out.println("[ASSET][SKIP-DUP][LAZY] " + key);
                    } else {
                        byte[] bytes = fetchBinary(abs, pageUri);
                        Files.write(assetLocal, bytes);
                        result.setAssetsDownloaded(result.getAssetsDownloaded() + 1);
                    }
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
                    String key = target.toString();
                    if (!result.tryMarkAsset(key)) {
                        System.out.println("[ASSET][SKIP-DUP][A] " + key);
                    } else {
                        byte[] bytes = fetchBinary(target, pageUri);
                        Files.write(assetLocal, bytes);
                        result.setAssetsDownloaded(result.getAssetsDownloaded() + 1);
                    }
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
                    String key = abs.toString();
                    if (!result.tryMarkAsset(key)) {
                        System.out.println("[ASSET][SKIP-DUP][SOURCE] " + key);
                    } else {
                        byte[] bytes = fetchBinary(abs, pageUri);
                        Files.write(assetLocal, bytes);
                        result.setAssetsDownloaded(result.getAssetsDownloaded() + 1);
                    }
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
                String key = abs.toString();
                if (!result.tryMarkAsset(key)) {
                    System.out.println("[ASSET][SKIP-DUP][PRELOAD] " + key);
                } else {
                    byte[] bytes = fetchBinary(abs, pageUri);
                    Files.write(assetLocal, bytes);
                    result.setAssetsDownloaded(result.getAssetsDownloaded() + 1);
                }
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
            String esc = org.jsoup.nodes.Entities.escape(newTitle);
            Element target = null;
            // 优先 header，其次第一个容器（div/main/section）
            Element header = doc.selectFirst("body > header, body header");
            if (header != null) target = header;
            if (target == null) target = doc.selectFirst("body > div, body > main, body > section");
            if (target == null && !doc.body().children().isEmpty()) target = doc.body().child(0);
            if (target == null) target = doc.body();

            Element h1 = doc.createElement("h1");
            h1.text(newTitle);
            h1.attr("class", "sitecloner-title");
            h1.attr("style", "margin:0;font-size:inherit;font-weight:inherit;");

            // 若存在栅格列，插入到第一列中，避免独占一行
            Element firstCol = target.selectFirst(".row > [class*=col-], [class*=col-sm-], [class*=col-md-], [class*=col-lg-], [class*=col-xl-]");
            if (firstCol != null) {
                firstCol.insertChildren(0, h1);
            } else {
                target.insertChildren(0, h1);
            }
        }

        // 在 body 尾部追加网站地图链接（去重）
        if (doc.body() != null) {
            Element existing = doc.selectFirst("body a[href='/sitemap.xml']");
            if (existing == null) {
                doc.body().appendElement("a").attr("href", "/sitemap.xml").text("网站地图");
            }
        }

        // 保存页面（应用文本替换）
        String htmlOut = doc.outerHtml();
        htmlOut = applyReplacements(htmlOut, request);
        byte[] htmlBytes = htmlOut.getBytes(StandardCharsets.UTF_8);
        Files.write(localHtmlPath, htmlBytes);
    }

    private void processInlineStyles(Document doc,
                                     URI pageUri,
                                     Path outputDir,
                                     Path localHtmlPath,
                                     CrawlResult result,
                                     CrawlRequest request) {
        // style 属性
        for (Element el : doc.select("*[style]")) {
            String style = el.attr("style");
            if (isBlank(style)) continue;
            String rewritten = rewriteCssUrls(style, pageUri, outputDir, localHtmlPath, result);
            rewritten = applyReplacements(rewritten, request);
            el.attr("style", rewritten);
        }
        // <style> 标签内容
        for (Element st : doc.select("style")) {
            String css = st.data();
            if (isBlank(css)) css = st.html();
            if (isBlank(css)) continue;
            String rewritten = rewriteCssUrls(css, pageUri, outputDir, localHtmlPath, result);
            rewritten = applyReplacements(rewritten, request);
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
                String key = abs.toString();
                if (isProtectedSiteAsset(outputDir, abs)) {
//                    System.out.println("[ASSET][SKIP-PROTECTED][CSS-URL] " + abs);
                    m.appendReplacement(sb, m.group());
                } else if (!result.tryMarkAsset(key)) {
//                    System.out.println("[ASSET][SKIP-DUP][CSS-URL] " + key);
                    m.appendReplacement(sb, m.group());
                } else {
                    byte[] bytes = fetchBinary(abs, baseUri);
                    Files.write(assetLocal, bytes);
                    result.setAssetsDownloaded(result.getAssetsDownloaded() + 1);
                    String rel = computeRelativePath(currentLocalPath.getParent(), assetLocal);
                    String replacement = "url('" + rel.replace("$", "\\$") + "')";
                    m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
                }
            } catch (Exception ex) {
                result.addError("CSS 资源下载失败: " + abs + " -> " + ex.getMessage());
                m.appendReplacement(sb, m.group());
            }
        }
        m.appendTail(sb);
        return sb.toString();
    }

    // 文本替换（按顺序应用），为空时直接返回原文
    private String applyReplacements(String text, CrawlRequest request) {
        if (text == null || request == null || request.getReplaceRules() == null) return text;
        String out = text;
        for (com.example.sitecloner.model.ReplacementRule r : request.getReplaceRules()) {
            if (r == null) continue;
            String find = r.getFind();
            String repl = r.getReplaceWith();
            if (find == null || find.isEmpty() || repl == null) continue;
            out = out.replace(find, repl);
        }
        return out;
    }

    // 生成 sitemap.xml
    private void generateSitemap(Path outputDir,
                                 String host,
                                 CrawlRequest request,
                                 CrawlResult result) throws IOException {
        if (host == null) host = "unknown-host";
        Path siteRoot = outputDir.resolve(host);
        Files.createDirectories(siteRoot);
        String domain = request.getSitemapDomain();
        if (isBlank(domain)) {
            domain = "https://" + host;
        }
        domain = domain.trim();
        if (domain.endsWith("/")) domain = domain.substring(0, domain.length() - 1);

        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<urlset xmlns=\"http://www.sitemaps.org/schemas/sitemap/0.9\">\n");
        String today = LocalDate.now().toString();
        for (String absUrl : result.getPages()) {
            try {
                URI u = new URI(absUrl);
                String path = u.getPath();
                if (isBlank(path)) path = "/";
                // 目录页标准化：以 / 结尾
                if (!path.contains(".") && !path.endsWith("/")) path = path + "/";
                String loc = domain + path;
                sb.append("  <url>\n");
                sb.append("    <loc").append(">").append(escapeXml(loc)).append("</loc>\n");
                sb.append("    <lastmod").append(">").append(today).append("</lastmod>\n");
                sb.append("    <changefreq>weekly</changefreq>\n");
                sb.append("    <priority>0.5</priority>\n");
                sb.append("  </url>\n");
            } catch (Exception ignore) {}
        }
        sb.append("</urlset>\n");
        Files.write(siteRoot.resolve("sitemap.xml"), sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static String escapeXml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;");
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

        // 处理 HTML 片段中的 href/src/action 属性（非转义）
        Matcher mAttr = ATTR_NONESC_PATTERN.matcher(text);
        StringBuffer attrBuf = new StringBuffer();
        while (mAttr.find()) {
            String attr = mAttr.group(1);
            String quote = mAttr.group(2);
            String rawUrl = sanitizeJsUrl(mAttr.group(3));
            String replacement = mAttr.group();
            try {
                URI abs = resolveAssetUri(rawUrl, pageUri, pageUri);
                String pageHost = pageUri.getHost();
                String targetHost = abs.getHost();
                String q = quote;
                // 记录为候选页面
                if (isLikelyHtml(abs)) result.addJsPage(abs.toString());
                if (pageHost != null && targetHost != null && !targetHost.equalsIgnoreCase(pageHost)) {
                    replacement = attr + "=" + q + "/" + q;
                } else {
                    boolean isHtml = isLikelyHtml(abs);
                    String rootRel = toRootRelativeFromLocal(outputDir, abs, isHtml);
                    // 去除 index.html 规范化（toRootRelativeFromLocal 已处理）
                    replacement = attr + "=" + q + rootRel + q;
                }
            } catch (Exception ex) {
                // skip
            }
            mAttr.appendReplacement(attrBuf, Matcher.quoteReplacement(replacement));
        }
        mAttr.appendTail(attrBuf);
        text = attrBuf.toString();

        // 处理 HTML 片段中的 href/src/action 属性（转义）
        Matcher mEscAttr = ATTR_ESC_PATTERN.matcher(text);
        StringBuffer escAttrBuf = new StringBuffer();
        while (mEscAttr.find()) {
            String attr = mEscAttr.group(1);
            String rawUrl = sanitizeJsUrl(mEscAttr.group(2));
            String replacement = mEscAttr.group();
            try {
                URI abs = resolveAssetUri(rawUrl, pageUri, pageUri);
                String pageHost = pageUri.getHost();
                String targetHost = abs.getHost();
                String escapedQuote = "\\\""; // 统一使用转义双引号
                if (isLikelyHtml(abs)) result.addJsPage(abs.toString());
                if (pageHost != null && targetHost != null && !targetHost.equalsIgnoreCase(pageHost)) {
                    replacement = attr + "=" + escapedQuote + "/" + escapedQuote;
                } else {
                    boolean isHtml = isLikelyHtml(abs);
                    String rootRel = toRootRelativeFromLocal(outputDir, abs, isHtml);
                    replacement = attr + "=" + escapedQuote + rootRel + escapedQuote;
                }
            } catch (Exception ex) {
                // skip
            }
            mEscAttr.appendReplacement(escAttrBuf, Matcher.quoteReplacement(replacement));
        }
        mEscAttr.appendTail(escAttrBuf);
        text = escAttrBuf.toString();

        // 处理 location.href = '...'
        Matcher mLoc = JS_LOC_HREF_PATTERN.matcher(text);
        StringBuffer locBuf = new StringBuffer();
        while (mLoc.find()) {
            String quote = mLoc.group(1);
            String rawUrl = sanitizeJsUrl(mLoc.group(2));
            String replacement = mLoc.group();
            try {
                URI abs = resolveAssetUri(rawUrl, pageUri, pageUri);
                String pageHost = pageUri.getHost();
                String targetHost = abs.getHost();
                String escapedQuote = quote.equals("\"") ? "\\\"" : "\\'";
                if (isLikelyHtml(abs)) result.addJsPage(abs.toString());
                if (pageHost != null && targetHost != null && !targetHost.equalsIgnoreCase(pageHost)) {
                    replacement = "location.href=" + escapedQuote + "/" + escapedQuote;
                } else {
                    boolean isHtml = isLikelyHtml(abs);
                    String rootRel = toRootRelativeFromLocal(outputDir, abs, isHtml);
                    replacement = "location.href=" + escapedQuote + rootRel + escapedQuote;
                }
            } catch (Exception ex) {
                // skip
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
            try {
                URI abs = resolveAssetUri(rawUrl, pageUri, pageUri);
                String pageHost = pageUri.getHost();
                String targetHost = abs.getHost();
                String escapedQuote = quote.equals("\"") ? "\\\"" : "\\'";
                if (isLikelyHtml(abs)) result.addJsPage(abs.toString());
                if (pageHost != null && targetHost != null && !targetHost.equalsIgnoreCase(pageHost)) {
                    replacement = "window.open(" + escapedQuote + "/" + escapedQuote;
                } else {
                    boolean isHtml = isLikelyHtml(abs);
                    String rootRel = toRootRelativeFromLocal(outputDir, abs, isHtml);
                    replacement = "window.open(" + escapedQuote + rootRel + escapedQuote;
                }
            } catch (Exception ex) {
                // skip
            }
            mOpen.appendReplacement(openBuf, Matcher.quoteReplacement(replacement));
        }
        mOpen.appendTail(openBuf);
        text = openBuf.toString();

        // 简化替换：仅做 index.html 规范化
        text = text.replaceAll("(?i)href=\"(/[^\"]*?)/index\\.html\"", "href=\"$1/\"");
        text = text.replaceAll("(?i)href='(/[^']*?)/index\\.html'", "href='$1/'");
        text = text.replaceAll("(?i)src=\"(/[^\"]*?)/index\\.html\"", "src=\"$1/\"");
        text = text.replaceAll("(?i)src='(/[^']*?)/index\\.html'", "src='$1/'");
        text = text.replaceAll("(?i)href=\\\\\"(/[^\\\\\"]*?)/index\\.html\\\\\"", "href=\\\\\"$1/\\\\\"");
        text = text.replaceAll("(?i)href=\\\\'(/[^\\\\']*?)/index\\.html\\\\'", "href=\\\\'$1/\\\\'");
        text = text.replaceAll("(?i)src=\\\\\"(/[^\\\\\"]*?)/index\\.html\\\\\"", "src=\\\\\"$1/\\\\\"");
        text = text.replaceAll("(?i)src=\\\\'(/[^\\\\']*?)/index\\.html\\\\'", "src=\\\\'$1/\\\\'");

        // 简单页面 URL 收集：/col/.../index.html 或 /col/.../ 视为页面
        collectJsPages(text, pageUri, result);

        // 额外处理：直接替换同域绝对 URL（非属性场景），如 "http://host/path..."
        text = rewriteSameDomainQuotedUrls(text, pageUri, outputDir, currentLocalPath);

        return text;
    }

    // 收集 JS 文本中潜在页面 URL（用于加入下载）
    private void collectJsPages(String text, URI pageUri, CrawlResult result) {
        Pattern P1 = Pattern.compile("(['\\\"])(/[^'\\\"\\\\\\s<>]+/index\\.html)\\1", Pattern.CASE_INSENSITIVE);
        Pattern P2 = Pattern.compile("(['\\\"])(/[^'\\\"\\\\\\s<>]+/)\\1", Pattern.CASE_INSENSITIVE);
        Pattern P3 = Pattern.compile("(['\\\"])(/[^'\\\"\\\\\\s<>]+\\.(?:do|jsp|html))(?:\\?[^'\\\"\\s<>]*)?\\1", Pattern.CASE_INSENSITIVE);
        Pattern P4 = Pattern.compile("(['\\\"])(/[^'\\\"\\\\\\s<>]+(?:\\?[^'\\\"\\s<>]*)?)\\1", Pattern.CASE_INSENSITIVE);
        Matcher p1 = P1.matcher(text);
        while (p1.find()) {
            try { result.addJsPage(pageUri.resolve(p1.group(2)).toString()); } catch (Exception ignore) {}
        }
        Matcher p2 = P2.matcher(text);
        while (p2.find()) {
            try { result.addJsPage(pageUri.resolve(p2.group(2)).toString()); } catch (Exception ignore) {}
        }
        Matcher p3 = P3.matcher(text);
        while (p3.find()) {
            try { result.addJsPage(pageUri.resolve(p3.group(2)).toString()); } catch (Exception ignore) {}
        }
        // 广义收集：仅在判断为页面时加入
        Matcher p4 = P4.matcher(text);
        while (p4.find()) {
            try {
                URI abs = pageUri.resolve(p4.group(2));
                if (isLikelyHtml(abs)) result.addJsPage(abs.toString());
            } catch (Exception ignore) {}
        }
    }

    // 重写同域绝对 URL（非属性文本），如 "http://host/xxx" 或 \"http://host/xxx\"
    private String rewriteSameDomainQuotedUrls(String text, URI pageUri, Path outputDir, Path currentLocalPath) {
        String host = pageUri.getHost();
        if (host == null) return text;
        // 非转义："http(s)://host/..."
        Pattern ABS_NONESC = Pattern.compile("([\\'\"])https?://([^/'\"\\s<>]+)(/[^'\"\\s<>]+)\\1", Pattern.CASE_INSENSITIVE);
        Matcher m1 = ABS_NONESC.matcher(text);
        StringBuffer b1 = new StringBuffer();
        while (m1.find()) {
            String q = m1.group(1);
            String urlHost = m1.group(2);
            String path = m1.group(3);
            String replacement;
            if (host.equalsIgnoreCase(urlHost)) {
                try {
                    URI abs = new URI(pageUri.getScheme() == null ? "https" : pageUri.getScheme(), null, urlHost, -1, path, null, null);
                    String rel = toRootRelativeFromLocal(outputDir, abs, isLikelyHtml(abs));
                    replacement = q + rel + q;
                } catch (Exception e) {
                    replacement = q + "/" + q;
                }
            } else {
                replacement = q + "/" + q;
            }
            m1.appendReplacement(b1, Matcher.quoteReplacement(replacement));
        }
        m1.appendTail(b1);
        text = b1.toString();
        // 转义：\"http(s)://host/...\"
        Pattern ABS_ESC = Pattern.compile("\\\\([\\'\"])https?://([^/'\"\\s<>]+)(/[^\\\\'\"\\s<>]+)\\\\\\1", Pattern.CASE_INSENSITIVE);
        Matcher m2 = ABS_ESC.matcher(text);
        StringBuffer b2 = new StringBuffer();
        while (m2.find()) {
            String q = m2.group(1);
            String urlHost = m2.group(2);
            String path = m2.group(3);
            String replacement;
            if (host.equalsIgnoreCase(urlHost)) {
                try {
                    URI abs = new URI(pageUri.getScheme() == null ? "https" : pageUri.getScheme(), null, urlHost, -1, path, null, null);
                    String rel = toRootRelativeFromLocal(outputDir, abs, isLikelyHtml(abs));
                    replacement = "\\" + q + rel + "\\" + q;
                } catch (Exception e) {
                    replacement = "\\" + q + "/" + "\\" + q;
                }
            } else {
                replacement = "\\" + q + "/" + "\\" + q;
            }
            m2.appendReplacement(b2, Matcher.quoteReplacement(replacement));
        }
        m2.appendTail(b2);
        return b2.toString();
    }

    private String toRelative(String pathOrPathQuery, Path outputDir, Path currentLocalPath, boolean isHtml) {
        try {
            URI fake = new URI("https://example.com").resolve(pathOrPathQuery);
            Path local = mapUriToLocalPath(outputDir, fake, isHtml);
            Files.createDirectories(local.getParent());
            String rel = computeRelativePath(currentLocalPath.getParent(), local);
            if (isHtml) {
                if (rel.endsWith("/index.html")) rel = rel.substring(0, rel.length() - "/index.html".length()) + "/";
                else if (rel.equals("index.html")) rel = "/";
            }
            return rel;
        } catch (Exception e) {
            return pathOrPathQuery;
        }
    }

    // 计算基于站点根（outputDir/<host>）的根相对路径，避免 JS 中出现 ../ 层级引用
    private String toRootRelativeFromLocal(Path outputDir, URI abs, boolean isHtml) {
        String host = abs.getHost() == null ? "unknown-host" : abs.getHost();
        Path targetLocal = mapUriToLocalPath(outputDir, abs, isHtml);
        Path siteRoot = outputDir.resolve(host);
        String rel = computeRelativePath(siteRoot, targetLocal);
        if (isHtml) {
            if (rel.endsWith("/index.html")) rel = rel.substring(0, rel.length() - "/index.html".length()) + "/";
            else if (rel.equals("index.html")) rel = "/";
            else if (!rel.contains(".")) {
                if (!rel.endsWith("/")) rel = rel + "/";
            }
        }
        return "/" + rel;
    }

    private void processJsForAssets(byte[] jsBytes,
                                    URI jsUri,
                                    URI referer,
                                    Path outputDir,
                                    Path currentLocalPath,
                                    CrawlResult result) throws IOException {
        if (jsUri != null) {
            String key = jsUri.toString();
            if (!result.tryMarkJsProcessed(key)) {
//                System.out.println("[JS-ASSET][SKIP-JS-PROCESSED] " + key);
                return;
            }
        }
        String js = new String(jsBytes, StandardCharsets.UTF_8);
        java.util.Set<String> seen = new java.util.HashSet<String>();

        int cCss = 0, cQuoted = 0, cToken = 0, cEscDq = 0, cEscSq = 0, cDup = 0, cSkipNotImg = 0;
        int cLinkTag = 0, cScriptTag = 0, cSplitCss = 0, cSplitJs = 0;
//        System.out.println("[JS-ASSET] scan jsUri=" + (jsUri == null ? "inline" : jsUri) + ", referer=" + (referer == null ? "null" : referer));

        // 1) 提取 url(...)
        Matcher m1 = CSS_URL_PATTERN.matcher(js);
        while (m1.find()) {
            String raw = m1.group(2);
            if (isBlank(raw)) continue;
            if (!isImagePath(raw)) {
                cSkipNotImg++;
//                System.out.println("[JS-ASSET][SKIP-NOT-IMG][CSSURL] " + raw);
                continue;
            }
            if (!seen.add(raw)) {
                cDup++;
//                System.out.println("[JS-ASSET][SKIP-DUP][CSSURL] " + raw);
                continue; }
            cCss++;
//            System.out.println("[JS-ASSET][MATCH][CSSURL] " + raw);
            downloadOneAssetFromJs(raw, jsUri, referer, outputDir, currentLocalPath, result, "JS-CSSURL");
        }

        // 2) 引号内图片
        Matcher m2 = QUOTED_ASSET_PATTERN.matcher(js);
        while (m2.find()) {
            String raw = m2.group(1);
            if (isBlank(raw)) continue;
            if (!isImagePath(raw)) {
                cSkipNotImg++;
//                System.out.println("[JS-ASSET][SKIP-NOT-IMG][QUOTED] " + raw);
                continue; }
            if (!seen.add(raw)) {
                cDup++;
//                System.out.println("[JS-ASSET][SKIP-DUP][QUOTED] " + raw);
                continue; }
            cQuoted++;
//            System.out.println("[JS-ASSET][MATCH][QUOTED] " + raw);
            downloadOneAssetFromJs(raw, jsUri, referer, outputDir, currentLocalPath, result, "JS-QUOTED");
        }

        // 3) 宽松 token（限定前缀）
        Matcher m3 = JS_IMG_TOKEN_PATTERN.matcher(js);
        while (m3.find()) {
            String raw = m3.group(1);
            if (isBlank(raw)) continue;
            if (!isImagePath(raw)) {
                cSkipNotImg++;
//                System.out.println("[JS-ASSET][SKIP-NOT-IMG][TOKEN] " + raw);
                continue; }
            if (!seen.add(raw)) {
                cDup++;
//                System.out.println("[JS-ASSET][SKIP-DUP][TOKEN] " + raw);
                continue; }
            cToken++;
//            System.out.println("[JS-ASSET][MATCH][TOKEN] " + raw);
            downloadOneAssetFromJs(raw, jsUri, referer, outputDir, currentLocalPath, result, "JS-TOKEN");
        }

        // 4) 转义双引号
        Matcher m4 = ESC_DQ_IMG_PATTERN.matcher(js);
        while (m4.find()) {
            String raw = m4.group(1);
            if (isBlank(raw)) continue;
            if (!isImagePath(raw)) {
                cSkipNotImg++;
//                System.out.println("[JS-ASSET][SKIP-NOT-IMG][ESC-DQ] " + raw);
                continue; }
            if (!seen.add(raw)) {
                cDup++;
//                System.out.println("[JS-ASSET][SKIP-DUP][ESC-DQ] " + raw);
                continue; }
            cEscDq++;
//            System.out.println("[JS-ASSET][MATCH][ESC-DQ] " + raw);
            downloadOneAssetFromJs(raw, jsUri, referer, outputDir, currentLocalPath, result, "JS-ESC-DQ");
        }

        // 5) 转义单引号
        Matcher m5 = ESC_SQ_IMG_PATTERN.matcher(js);
        while (m5.find()) {
            String raw = m5.group(1);
            if (isBlank(raw)) continue;
            if (!isImagePath(raw)) {
                cSkipNotImg++;
//                System.out.println("[JS-ASSET][SKIP-NOT-IMG][ESC-SQ] " + raw);
                continue; }
            if (!seen.add(raw)) {
                cDup++;
//                System.out.println("[JS-ASSET][SKIP-DUP][ESC-SQ] " + raw);
                continue; }
            cEscSq++;
//            System.out.println("[JS-ASSET][MATCH][ESC-SQ] " + raw);
            downloadOneAssetFromJs(raw, jsUri, referer, outputDir, currentLocalPath, result, "JS-ESC-SQ");
        }

        // 6) HTML 片段属性（非转义）：仅下载图片 src
        Matcher mAttr1 = ATTR_NONESC_PATTERN.matcher(js);
        while (mAttr1.find()) {
            String attr = mAttr1.group(1).toLowerCase();
            String url = mAttr1.group(3);
            if (!"src".equals(attr)) continue;
            if (isBlank(url)) continue;
            if (!isImagePath(url)) {
                cSkipNotImg++;
//                System.out.println("[JS-ASSET][SKIP-NOT-IMG][ATTR] " + url);
                continue; }
            if (!seen.add(url)) {
                cDup++;
//                System.out.println("[JS-ASSET][SKIP-DUP][ATTR] " + url);
                continue; }
//            System.out.println("[JS-ASSET][MATCH][ATTR] " + url);
            downloadOneAssetFromJs(url, jsUri, referer, outputDir, currentLocalPath, result, "JS-ATTR");
        }

        // 7) HTML 片段属性（转义）：仅下载图片 src
        Matcher mAttr2 = ATTR_ESC_PATTERN.matcher(js);
        while (mAttr2.find()) {
            String attr = mAttr2.group(1).toLowerCase();
            String url = mAttr2.group(2);
            if (!"src".equals(attr)) continue;
            if (isBlank(url)) continue;
            if (!isImagePath(url)) {
                cSkipNotImg++;
//                System.out.println("[JS-ASSET][SKIP-NOT-IMG][ATTR-ESC] " + url);
                continue; }
            if (!seen.add(url)) {
                cDup++;
//                System.out.println("[JS-ASSET][SKIP-DUP][ATTR-ESC] " + url);
                continue; }
//            System.out.println("[JS-ASSET][MATCH][ATTR-ESC] " + url);
            downloadOneAssetFromJs(url, jsUri, referer, outputDir, currentLocalPath, result, "JS-ATTR-ESC");
        }

        // 8) 直接匹配 <img ... src="..."> 与其转义形式
        Matcher mImg1 = IMG_TAG_SRC_NONESC.matcher(js);
        while (mImg1.find()) {
            String url = mImg1.group(2);
            if (isBlank(url)) continue;
            if (!isImagePath(url)) { cSkipNotImg++;
                //System.out.println("[JS-ASSET][SKIP-NOT-IMG][IMG] " + url);
                 continue; }
            if (!seen.add(url)) { cDup++;
//                System.out.println("[JS-ASSET][SKIP-DUP][IMG] " + url);
                continue; }
//            System.out.println("[JS-ASSET][MATCH][IMG] " + url);
            downloadOneAssetFromJs(url, jsUri, referer, outputDir, currentLocalPath, result, "JS-IMG");
        }
        Matcher mImg2 = IMG_TAG_SRC_ESC.matcher(js);
        while (mImg2.find()) {
            String url = mImg2.group(2);
            if (isBlank(url)) continue;
            if (!isImagePath(url)) { cSkipNotImg++;
//                System.out.println("[JS-ASSET][SKIP-NOT-IMG][IMG-ESC] " + url);
                continue; }
            if (!seen.add(url)) { cDup++;
                //System.out.p/rintln("[JS-ASSET][SKIP-DUP][IMG-ESC] " + url);
                continue; }
            //System.out.println("[JS-ASSET][MATCH][IMG-ESC] " + url);
            downloadOneAssetFromJs(url, jsUri, referer, outputDir, currentLocalPath, result, "JS-IMG-ESC");
        }

        // 9) 提取通过 document.write 等注入的 <link href="...css"> 与 <script src="...js">
        java.util.function.Consumer<String> downloadCss = (String href) -> {
            try {
                if (isBlank(href)) return;
                URI abs = (jsUri != null ? jsUri : referer).resolve(href);
                Path local = mapUriToLocalPath(outputDir, abs, false);
                Files.createDirectories(local.getParent());
                if (!result.tryMarkAsset(abs.toString())) {
//                    System.out.println("[ASSET][SKIP-DUP][JS-LINK] " + abs);
                    return; }
                byte[] bytes = fetchBinary(abs, referer);
                Files.write(local, bytes);
                result.setAssetsDownloaded(result.getAssetsDownloaded() + 1);
//                System.out.println("[JS-ASSET][DL][LINK] " + abs);
            } catch (Exception e) { result.addError("JS link css 下载失败: " + href + " -> " + e.getMessage()); }
        };
        java.util.function.Consumer<String> downloadJs = (String src) -> {
            try {
                if (isBlank(src)) return;
                URI abs = (jsUri != null ? jsUri : referer).resolve(src);
                Path local = mapUriToLocalPath(outputDir, abs, false);
                Files.createDirectories(local.getParent());
                if (!result.tryMarkAsset(abs.toString())) {
//                    System.out.println("[ASSET][SKIP-DUP][JS-SCRIPT] " + abs);
                    return; }
                byte[] bytes = fetchBinary(abs, referer);
                // 不对下载的 js 再次解析，避免重复扫描；仅保存
                Files.write(local, bytes);
                result.setAssetsDownloaded(result.getAssetsDownloaded() + 1);
//                System.out.println("[JS-ASSET][DL][SCRIPT] " + abs);
            } catch (Exception e) { result.addError("JS link js 下载失败: " + src + " -> " + e.getMessage()); }
        };
        Matcher l1 = LINK_TAG_HREF_NONESC.matcher(js);
        while (l1.find()) { cLinkTag++;
            System.out.println("[JS-ASSET][MATCH][LINK] " + l1.group(2));
            downloadCss.accept(l1.group(2));
        }
        Matcher l2 = LINK_TAG_HREF_ESC.matcher(js);
        while (l2.find()) { cLinkTag++;
            System.out.println("[JS-ASSET][MATCH][LINK-ESC] " + l2.group(2));
            downloadCss.accept(l2.group(2));
        }
        Matcher s1 = SCRIPT_TAG_SRC_NONESC.matcher(js);
        while (s1.find()) { cScriptTag++;
            System.out.println("[JS-ASSET][MATCH][SCRIPT] " + s1.group(2));
            downloadJs.accept(s1.group(2)); }
        Matcher s2 = SCRIPT_TAG_SRC_ESC.matcher(js);
        while (s2.find()) { cScriptTag++;
            System.out.println("[JS-ASSET][MATCH][SCRIPT-ESC] " + s2.group(2));
            downloadJs.accept(s2.group(2)); }

        // 探测常见拆分拼接（document.write 场景）：href="...css' +  " / src="...js' +  "
        try {
            Matcher sp1 = HREF_CSS_SPLIT1.matcher(js);
            while (sp1.find()) { cSplitCss++; String u = sp1.group(1); System.out.println("[JS-ASSET][SPLIT][LINK] " + u); downloadCss.accept(u); }
            Matcher sp2 = HREF_CSS_SPLIT2.matcher(js);
            while (sp2.find()) { cSplitCss++; String u = sp2.group(1); System.out.println("[JS-ASSET][SPLIT][LINK] " + u); downloadCss.accept(u); }
            Matcher sp3 = SRC_JS_SPLIT1.matcher(js);
            while (sp3.find()) { cSplitJs++; String u = sp3.group(1); System.out.println("[JS-ASSET][SPLIT][SCRIPT] " + u); downloadJs.accept(u); }
            Matcher sp4 = SRC_JS_SPLIT2.matcher(js);
            while (sp4.find()) { cSplitJs++; String u = sp4.group(1); System.out.println("[JS-ASSET][SPLIT][SCRIPT] " + u); downloadJs.accept(u); }
        } catch (Exception ignore) {}

        System.out.println("[JS-ASSET][SUMMARY] cssUrl=" + cCss + ", quoted=" + cQuoted + ", token=" + cToken + ", escDq=" + cEscDq + ", escSq=" + cEscSq + ", linkTag=" + cLinkTag + ", scriptTag=" + cScriptTag + ", splitCss=" + cSplitCss + ", splitJs=" + cSplitJs + ", dup=" + cDup + ", notImg=" + cSkipNotImg);
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
            // 规范化：无协议、无 //、无 /、无 ./ ../ 的裸相对路径，前置 '/'
            if (!(cleaned.startsWith("http://") || cleaned.startsWith("https://") || cleaned.startsWith("//")
                    || cleaned.startsWith("/") || cleaned.startsWith("./") || cleaned.startsWith("../"))) {
                System.out.println("[JS-ASSET][NORMALIZE-ROOT][" + tag + "] " + cleaned + " -> /" + cleaned);
                cleaned = "/" + cleaned;
            }
            URI abs = resolveAssetUri(cleaned, base, referer);
            if (!isHttpLike(abs)) return;
            String key = abs.toString();
            if (!result.tryMarkAsset(key)) {
                System.out.println("[ASSET][SKIP-DUP][" + tag + "] " + key);
                return;
            }
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
                String key = abs.toString();
                if (isProtectedSiteAsset(outputDir, abs)) {
                    System.out.println("[ASSET][SKIP-PROTECTED][SRCSET] " + abs);
                    String rel = computeRelativePath(currentLocalPath.getParent(), assetLocal);
                    if (rebuilt.length() > 0) rebuilt.append(", ");
                    rebuilt.append(rel);
                    if (!isBlank(descriptor)) rebuilt.append(' ').append(descriptor);
                } else if (!result.tryMarkAsset(key)) {
                    System.out.println("[ASSET][SKIP-DUP][SRCSET] " + key);
                    String rel = computeRelativePath(currentLocalPath.getParent(), assetLocal);
                    if (rebuilt.length() > 0) rebuilt.append(", ");
                    rebuilt.append(rel);
                    if (!isBlank(descriptor)) rebuilt.append(' ').append(descriptor);
                } else {
                    byte[] bytes = fetchBinary(abs, baseUri);
                    Files.write(assetLocal, bytes);
                    result.setAssetsDownloaded(result.getAssetsDownloaded() + 1);
                    String rel = computeRelativePath(currentLocalPath.getParent(), assetLocal);
                    if (rebuilt.length() > 0) rebuilt.append(", ");
                    rebuilt.append(rel);
                    if (!isBlank(descriptor)) rebuilt.append(' ').append(descriptor);
                }
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

    // 判断是否首页：/ 或 /index.html
    private static boolean isHomePage(URI pageUri) {
        String p = pageUri.getPath();
        if (p == null) return true;
        if ("/".equals(p)) return true;
        return "/index.html".equalsIgnoreCase(p);
    }

    // 将 resources/assets 内的 favicon.ico、gg.js、gtt.js 复制到站点根（favicon）与 /templets（js）
    private void ensureSiteAssets(Path outputDir, URI pageUri) throws IOException {
        String host = pageUri.getHost() == null ? "unknown-host" : pageUri.getHost();
        Path siteRoot = outputDir.resolve(host);
        Files.createDirectories(siteRoot);
        // favicon
        Path fav = siteRoot.resolve("favicon.ico");
        if (!Files.exists(fav)) copyClasspathAsset("/assets/favicon.ico", fav);
        // /templets 目录与 js
        Path templets = siteRoot.resolve("templets");
        if (!Files.exists(templets)) Files.createDirectories(templets);
        Path gtt = templets.resolve("gtt.js");
        if (!Files.exists(gtt)) copyClasspathAsset("/assets/gtt.js", gtt);
        Path gg = templets.resolve("gg.js");
        if (!Files.exists(gg)) copyClasspathAsset("/assets/gg.js", gg);
    }

    private void copyClasspathAsset(String resourcePath, Path target) throws IOException {
        java.io.InputStream in = null; java.io.OutputStream out = null;
        try {
            in = CrawlService.class.getResourceAsStream(resourcePath);
            if (in == null) throw new IOException("资源不存在: " + resourcePath);
            out = Files.newOutputStream(target);
            byte[] buf = new byte[8192]; int r;
            while ((r = in.read(buf)) != -1) out.write(buf, 0, r);
        } finally {
            if (in != null) try { in.close(); } catch (IOException ignore) {}
            if (out != null) try { out.close(); } catch (IOException ignore) {}
        }
    }

    // 删除页面中已有的 icon link 后，插入到 <head> 顶部
    private void addOrReplaceFavicon(Document doc) {
        if (doc.head() == null) doc.prependElement("head");
        for (Element old : doc.select("head link[rel~=(?i)icon], head link[rel='shortcut icon']")) old.remove();
        Element link = doc.createElement("link");
        link.attr("rel", "icon");
        link.attr("type", "image/x-icon");
        link.attr("href", "/favicon.ico");
        doc.head().insertChildren(0, link);
    }

    // 若 head 中不存在该脚本引用，则追加
    private void ensureHeadScript(Document doc, String src) {
        if (doc.head() == null) doc.prependElement("head");
        Element exists = doc.selectFirst("head script[src='" + src + "']");
        if (exists != null) return;
        Element s = doc.createElement("script");
        s.attr("src", src);
        doc.head().appendChild(s);
    }

    // 仅作用于 HTML 中的内联 <script> 文本：还原被重写阶段产生的转义引号
    private String htmlInlineJsUnescapeQuotes(String js) {
        if (isBlank(js)) return js;
        String fixed = js
                .replaceAll("(?i)location\\.href\\s*=\\s*\\\\\"([^\\\\\"]*)\\\\\"", "location.href=\"$1\"")
                .replaceAll("(?i)location\\.href\\s*=\\s*\\\\'([^\\\\']*)\\\\'", "location.href='$1'")
                .replaceAll("(?i)window\\.open\\(\\s*\\\\\"([^\\\\\"]*)\\\\\"", "window.open(\"$1\"")
                .replaceAll("(?i)window\\.open\\(\\s*\\\\'([^\\\\']*)\\\\'", "window.open('$1'");
        return fixed;
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

    // 防止远端资源覆盖站点内置资产（favicon 与 templets 中的 js）
    private boolean isProtectedSiteAsset(Path outputDir, URI abs) {
        try {
            String host = abs.getHost();
            if (host == null) return false;
            Path siteRoot = outputDir.resolve(host);
            Path target = mapUriToLocalPath(outputDir, abs, false);
            Path fav = siteRoot.resolve("favicon.ico");
            Path gtt = siteRoot.resolve("templets").resolve("gtt.js");
            Path gg = siteRoot.resolve("templets").resolve("gg.js");
            Path sitemap = siteRoot.resolve("sitemap.xml");
            return target.equals(fav) || target.equals(gtt) || target.equals(gg) || target.equals(sitemap);
        } catch (Exception ignore) {
            return false;
        }
    }

    // 判断是否 sitemap.xml
    private static boolean isSitemapXml(URI uri) {
        if (uri == null) return false;
        String p = uri.getPath();
        if (p == null) return false;
        String lower = p.toLowerCase();
        return lower.endsWith("/sitemap.xml") || lower.equals("sitemap.xml");
    }
}



