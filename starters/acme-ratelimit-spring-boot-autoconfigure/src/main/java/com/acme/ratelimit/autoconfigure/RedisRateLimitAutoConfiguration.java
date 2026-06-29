package com.acme.ratelimit.autoconfigure;

import com.giffing.bucket4j.spring.boot.starter.config.cache.Bucket4jCacheConfiguration;
import com.giffing.bucket4j.spring.boot.starter.config.cache.SyncCacheResolver;
import io.github.bucket4j.distributed.ExpirationAfterWriteStrategy;
import io.github.bucket4j.distributed.proxy.AbstractProxyManager;
import io.github.bucket4j.redis.lettuce.Bucket4jLettuce;
import io.github.bucket4j.redis.lettuce.cas.LettuceBasedProxyManager;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.codec.ByteArrayCodec;
import java.time.Duration;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

/**
 * Optional <strong>distributed</strong> rate-limit backend: a Bucket4j Redis (Lettuce)
 * {@code LettuceBasedProxyManager} that stores buckets in Redis so a per-caller limit is enforced
 * <strong>cluster-wide</strong>, not per replica.
 *
 * <p>Activated only when:
 *
 * <ul>
 *   <li>{@code acme.ratelimit.backend=redis} — opt-in selector (default is {@code local}); and
 *   <li>{@code bucket4j.enabled=true} — rate-limiting is on; and
 *   <li>Bucket4j's Lettuce ProxyManager and the Lettuce client are on the classpath (typically via
 *       {@code spring-boot-starter-data-redis} + {@code bucket4j_jdk17-lettuce}).
 * </ul>
 *
 * <p>When this backend is active it provides a {@link SyncCacheResolver} (a
 * {@link RedisProxyManagerCacheResolver}) and the default in-process {@link RateLimitAutoConfiguration}
 * steps aside (its {@code backend=local} condition no longer matches). The Redis connection is built
 * from the standard {@code spring.data.redis.*} properties; supply your own {@link RedisClient} bean
 * to reuse an existing Lettuce connection.
 *
 * <p>If {@code backend=redis} but the Redis classes are absent, neither this nor the local backend
 * registers a resolver — {@link RedisRateLimitMissingDependencyAutoConfiguration} fails startup with
 * a clear, actionable message rather than Bucket4j's cryptic "No Bucket4j cache configuration found".
 */
@AutoConfiguration(before = Bucket4jCacheConfiguration.class)
@ConditionalOnClass({RedisClient.class, LettuceBasedProxyManager.class})
@ConditionalOnProperty(prefix = "bucket4j", name = "enabled", havingValue = "true")
@ConditionalOnProperty(prefix = "acme.ratelimit", name = "backend", havingValue = "redis")
@EnableConfigurationProperties(RateLimitProperties.class)
public class RedisRateLimitAutoConfiguration {

    /**
     * The raw Lettuce {@link RedisClient} used by the distributed ProxyManager, built from the
     * standard {@code spring.data.redis.*} properties. {@link ConditionalOnMissingBean} so a
     * consumer-provided client (e.g. one sharing their existing Lettuce connection) wins.
     */
    @Bean(destroyMethod = "shutdown")
    @ConditionalOnMissingBean
    public RedisClient rateLimitRedisClient(Environment environment) {
        RedisProperties properties = Binder.get(environment)
                .bind("spring.data.redis", RedisProperties.class)
                .orElseGet(RedisProperties::new);
        RedisURI.Builder uri = RedisURI.builder()
                .withHost(properties.getHost() != null ? properties.getHost() : "localhost")
                .withPort(properties.getPort() > 0 ? properties.getPort() : 6379)
                .withSsl(properties.getSsl() != null && properties.getSsl().isEnabled());
        if (properties.getPassword() != null) {
            if (properties.getUsername() != null) {
                uri.withAuthentication(
                        properties.getUsername(), properties.getPassword().toCharArray());
            } else {
                uri.withPassword(properties.getPassword().toCharArray());
            }
        }
        if (properties.getDatabase() != 0) {
            uri.withDatabase(properties.getDatabase());
        }
        return RedisClient.create(uri.build());
    }

    /**
     * The distributed {@link AbstractProxyManager} backing every rate-limit bucket. Built via the
     * shared {@link #lettuceProxyManager(RedisClient, Duration)} factory so production and the
     * distributed integration test exercise identical wiring.
     */
    @Bean
    @ConditionalOnMissingBean
    public AbstractProxyManager<byte[]> rateLimitProxyManager(
            RedisClient rateLimitRedisClient, RateLimitProperties properties) {
        return lettuceProxyManager(rateLimitRedisClient, properties.getRedis().getBucketTtl());
    }

    /**
     * Bucket4j's servlet (synchronous) cache resolver, backed by the distributed Redis ProxyManager.
     * With a {@link SyncCacheResolver} bean present, Bucket4j's servlet filter is created and every
     * bucket is resolved from Redis — so the limit is shared across replicas.
     */
    @Bean
    @ConditionalOnMissingBean(SyncCacheResolver.class)
    public SyncCacheResolver bucket4jSyncCacheResolver(AbstractProxyManager<byte[]> rateLimitProxyManager) {
        return new RedisProxyManagerCacheResolver(rateLimitProxyManager);
    }

    /**
     * Builds a Bucket4j {@link LettuceBasedProxyManager} over the given Lettuce client. Keys expire
     * once a bucket would be fully refilled (bounded keyspace for per-caller limiting). Shared by the
     * auto-configuration and the distributed IT so both prove the same mechanism.
     */
    public static LettuceBasedProxyManager<byte[]> lettuceProxyManager(RedisClient client, Duration bucketTtl) {
        // A byte[]/byte[] connection: Bucket4j stores serialized bucket state as raw bytes. The
        // connection lives for the client's lifetime and is closed when the RedisClient shuts down.
        StatefulRedisConnection<byte[], byte[]> connection = client.connect(ByteArrayCodec.INSTANCE);
        return Bucket4jLettuce.casBasedBuilder(connection)
                .expirationAfterWrite(ExpirationAfterWriteStrategy.basedOnTimeForRefillingBucketUpToMax(bucketTtl))
                .build();
    }
}
