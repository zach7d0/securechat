package com.securechat.support.web;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.Ordered;

/** Correlation ids (first filter, before security) and problem-detail error mapping. */
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@Import(ProblemHandler.class)
public class WebSupportConfiguration {
    @Bean
    FilterRegistrationBean<CorrelationIdFilter> correlationIdFilter() {
        FilterRegistrationBean<CorrelationIdFilter> r = new FilterRegistrationBean<>(new CorrelationIdFilter());
        r.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return r;
    }
}
