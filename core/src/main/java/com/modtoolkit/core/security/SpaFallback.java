package com.modtoolkit.core.security;

import jakarta.servlet.http.HttpServletRequest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;
import org.springframework.web.servlet.resource.ResourceResolverChain;

/**
 * Serves the Angular build from a folder, as the proxy's {@code try_files $uri $uri/ /index.html} did: a file when one
 * matches, else index.html for a page route, so a bookmarked /review loads the app. Nothing under the API, MCP,
 * actuator or OpenAPI paths ever falls back. Without a UI folder the server runs API and MCP only.
 */
@Configuration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(name = "toolkit.ui.enabled", havingValue = "true")
public class SpaFallback implements WebMvcConfigurer {
    private static final Logger log = LoggerFactory.getLogger(SpaFallback.class);
    private static final List<String> NEVER = List.of("api", "mcp", "actuator", "v3/api-docs", "error");

    private final Optional<Path> dir;

    public SpaFallback(Environment env) {
        this.dir = uiDir(env.getProperty("toolkit.ui.dir", ""), env.getProperty("TOOLKIT_HOME", ""));
        if (dir.isPresent()) log.info("serving the web UI from {}", dir.get());
        else log.info("no web UI folder (toolkit.ui.dir or TOOLKIT_HOME/ui with an index.html); serving the API and MCP only");
    }

    /** The configured folder, else the install's ui folder; only one holding an index.html counts. */
    static Optional<Path> uiDir(String configured, String home) {
        Path p = !configured.isBlank() ? Path.of(configured) : !home.isBlank() ? Path.of(home, "ui") : null;
        if (p == null) return Optional.empty();
        if (!Files.isRegularFile(p.resolve("index.html"))) {
            if (!configured.isBlank()) log.warn("toolkit.ui.dir {} has no index.html; the web UI is off", p);
            return Optional.empty();
        }
        return Optional.of(p.toAbsolutePath().normalize());
    }

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        if (dir.isPresent()) registry.addViewController("/").setViewName("forward:/index.html");
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        if (dir.isEmpty()) return;
        String location = dir.get().toUri().toString();
        // no-cache still lets the browser revalidate with Last-Modified; the build's hashed names change on upgrade.
        registry.addResourceHandler("/**").addResourceLocations(location.endsWith("/") ? location : location + "/")
                .setCacheControl(CacheControl.noCache()).resourceChain(false).addResolver(new IndexFallback());
    }

    /** A page route: not a reserved path, and either asked for as HTML or without a file extension. */
    static boolean isPageRoute(String path, String accept) {
        String p = path.startsWith("/") ? path.substring(1) : path;
        for (String n : NEVER) if (p.equals(n) || p.startsWith(n + "/")) return false;
        if (accept != null && accept.contains("text/html")) return true;
        String last = p.substring(p.lastIndexOf('/') + 1);
        return !last.contains(".");
    }

    static final class IndexFallback extends PathResourceResolver {
        @Override
        protected Resource resolveResourceInternal(HttpServletRequest req, String path, List<? extends Resource> locations, ResourceResolverChain chain) {
            Resource r = super.resolveResourceInternal(req, path, locations, chain);
            if (r != null || req == null || !isPageRoute(path, req.getHeader("Accept"))) return r;
            return super.resolveResourceInternal(req, "index.html", locations, chain);
        }
    }
}
