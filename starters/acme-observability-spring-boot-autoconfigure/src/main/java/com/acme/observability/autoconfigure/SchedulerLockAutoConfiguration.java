package com.acme.observability.autoconfigure;

import com.acme.observability.ObservabilityProperties;
import javax.sql.DataSource;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Configures a DB-time JDBC {@link LockProvider} and enables ShedLock so {@code @Scheduled}
 * jobs run on exactly one instance. {@code usingDbTime()} makes locking resilient to clock skew
 * across nodes (DB-portable: Postgres + Oracle).
 */
@AutoConfiguration
@ConditionalOnClass(LockProvider.class)
@ConditionalOnBean(DataSource.class)
@EnableConfigurationProperties(ObservabilityProperties.class)
@EnableSchedulerLock(defaultLockAtMostFor = "${acme.observability.scheduler-lock.default-lock-at-most-for:PT10M}")
public class SchedulerLockAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public LockProvider lockProvider(DataSource dataSource) {
        return new JdbcTemplateLockProvider(JdbcTemplateLockProvider.Configuration.builder()
                .withJdbcTemplate(new JdbcTemplate(dataSource))
                .usingDbTime()
                .build());
    }
}
