package com.toenshoffr.bff;

import com.toenshoffr.bff.config.BffProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.support.SpringBootServletInitializer;

/**
 * Entry point. Extends {@link SpringBootServletInitializer} so the exact same app
 * boots whether launched via {@code java -jar} (embedded Tomcat) or deployed as a
 * WAR into an external Tomcat — see SPEC.md "Build & Packaging (Maven)" and the
 * README's "Deploying to Tomcat" section.
 *
 * <p>{@link UserDetailsServiceAutoConfiguration} is excluded: this BFF implements its
 * own auth endpoints (see the {@code auth} package) rather than Spring Security's
 * default form-login/httpBasic, so Boot's auto-generated single-user login and its
 * "Using generated security password" startup log line would only be noise/confusion.
 */
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
@EnableConfigurationProperties(BffProperties.class)
public class BffApplication extends SpringBootServletInitializer {

    public static void main(String[] args) {
        SpringApplication.run(BffApplication.class, args);
    }

    @Override
    protected SpringApplicationBuilder configure(SpringApplicationBuilder builder) {
        return builder.sources(BffApplication.class);
    }
}
