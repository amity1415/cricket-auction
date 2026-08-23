package com.auctiontracker.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.time.Duration;

/**
 * Team crest logos under {@code /img/**} are versioned by a {@code ?v=N} query
 * (see team-logo.js) and change only when that bumps, so they must be cached
 * hard rather than revalidated. The app-wide default (spring.web.resources.cache
 * .cachecontrol.no-cache=true) forces JS/CSS to revalidate on every load — good
 * for code, but it makes the logos re-fetch each time an <img> is (re)created by
 * a render tick, which showed up as the team images flickering on the boards.
 * Serving {@code /img/**} immutable with a long max-age lets the browser reuse
 * the decoded image with no network round-trip, so recreated tiles never flash.
 */
@Configuration
public class StaticCacheConfig implements WebMvcConfigurer {

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/img/**")
                .addResourceLocations("classpath:/static/img/")
                .setCacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePublic().immutable());
    }
}
