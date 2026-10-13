package com.runpulse.server.config;

import java.io.IOException;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;

/**
 * 托管 Vue3 构建产物（classpath:/static/admin/）并支持 SPA history 路由 fallback。
 * 真实存在的文件（js/css/assets 等）直接返回，其余 /admin/** 路径回退到 index.html。
 */
@Configuration
public class AdminWebConfig implements WebMvcConfigurer {

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // APK 文件下载服务
        registry.addResourceHandler("/apk/**")
                .addResourceLocations("file:/opt/runpulse/apk/");

        registry.addResourceHandler("/admin/**")
                .addResourceLocations("classpath:/static/admin/")
                .resourceChain(true)
                .addResolver(new PathResourceResolver() {
                    @Override
                    protected Resource getResource(String resourcePath, Resource location) throws IOException {
                        // /admin/ 目录请求直接给首页
                        if (resourcePath.isEmpty() || resourcePath.endsWith("/")) {
                            return new ClassPathResource("/static/admin/index.html");
                        }
                        Resource requested = location.createRelative(resourcePath);
                        if (requested.exists() && requested.isReadable() && requested.contentLength() > 0) {
                            return requested;
                        }
                        // 带指纹的 assets 找不到就真 404；页面路由统一回退 index.html
                        if (resourcePath.startsWith("assets/")) {
                            return null;
                        }
                        return new ClassPathResource("/static/admin/index.html");
                    }
                });
    }

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        registry.addRedirectViewController("/", "/admin/");
        // Spring 6 默认关闭尾部斜杠匹配，/admin 与 /admin/ 需显式转发到首页
        registry.addViewController("/admin").setViewName("forward:/admin/index.html");
        registry.addViewController("/admin/").setViewName("forward:/admin/index.html");
    }
}
