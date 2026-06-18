package com.acme.web.autoconfigure;

import com.acme.web.IdempotencyFilter;
import com.acme.web.IdempotencyStore;
import com.acme.web.InMemoryIdempotencyStore;
import com.acme.web.RedisIdempotencyStore;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.DispatcherServlet;

/**
 * Wires the idempotency store + filter. A shared {@link RedisIdempotencyStore} is preferred when a
 * {@link StringRedisTemplate} is on the context (multi-instance deployments); otherwise the
 * in-memory store is the single-instance default.
 *
 * <p>Ordered {@code after = RedisAutoConfiguration.class}: {@code @ConditionalOnBean} only sees
 * beans contributed by auto-configurations that already ran, so Redis must be configured first or
 * the Redis store would never activate (recurring repo invariant).
 */
@AutoConfiguration(after = RedisAutoConfiguration.class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass(DispatcherServlet.class)
public class IdempotencyAutoConfiguration {

    /** Shared store: active only when Redis (a {@link StringRedisTemplate}) is present. */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(StringRedisTemplate.class)
    static class RedisStoreConfiguration {

        @Bean
        @ConditionalOnMissingBean(IdempotencyStore.class)
        @ConditionalOnBean(StringRedisTemplate.class)
        public IdempotencyStore acmeRedisIdempotencyStore(StringRedisTemplate redisTemplate) {
            return new RedisIdempotencyStore(redisTemplate);
        }
    }

    /** Fallback store: in-memory, used whenever no other store (e.g. Redis) was contributed. */
    @Bean
    @ConditionalOnMissingBean(IdempotencyStore.class)
    public IdempotencyStore acmeIdempotencyStore() {
        return new InMemoryIdempotencyStore();
    }

    @Bean
    @ConditionalOnProperty(
            prefix = "acme.web.idempotency",
            name = "enabled",
            havingValue = "true",
            matchIfMissing = true)
    public IdempotencyFilter acmeIdempotencyFilter(IdempotencyStore store) {
        return new IdempotencyFilter(store);
    }
}
