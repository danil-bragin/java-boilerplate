package com.acme.web.autoconfigure;

import com.acme.web.error.ProblemExceptionHandler;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.web.servlet.DispatcherServlet;

/** Registers the {@link ProblemExceptionHandler} when on a servlet web app, unless overridden. */
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass(DispatcherServlet.class)
@ConditionalOnProperty(prefix = "acme.web.problem", name = "enabled", havingValue = "true", matchIfMissing = true)
public class AcmeWebAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public ProblemExceptionHandler acmeProblemExceptionHandler() {
        return new ProblemExceptionHandler();
    }
}
