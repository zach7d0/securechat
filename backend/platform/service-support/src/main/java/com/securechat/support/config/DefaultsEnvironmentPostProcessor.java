package com.securechat.support.config;

import java.io.IOException;
import java.io.UncheckedIOException;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

/**
 * Adds {@code securechat-defaults.yml} as the LOWEST-precedence property source, so a service's own
 * application.yml, environment variables and test properties always override the shared defaults.
 * (A plain {@code spring.config.import} would give the imported file higher precedence.)
 */
public class DefaultsEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {
    @Override
    public void postProcessEnvironment(ConfigurableEnvironment env, SpringApplication app) {
        ClassPathResource res = new ClassPathResource("securechat-defaults.yml");
        if (!res.exists() || env.getPropertySources().contains("securechat-defaults")) {
            return;
        }
        try {
            for (PropertySource<?> ps : new YamlPropertySourceLoader().load("securechat-defaults", res)) {
                env.getPropertySources().addLast(ps);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
}
