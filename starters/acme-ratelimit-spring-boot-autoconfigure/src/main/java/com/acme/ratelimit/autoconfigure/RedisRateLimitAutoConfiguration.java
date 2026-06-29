package com.acme.ratelimit.autoconfigure;

import com.giffing.bucket4j.spring.boot.starter.config.cache.Bucket4jCacheConfiguration;
import com.giffing.bucket4j.spring.boot.starter.config.cache.SyncCacheResolver;
import com.giffing.bucket4j.spring.boot.starter.context.properties.BandWidth;
import com.giffing.bucket4j.spring.boot.starter.context.properties.Bucket4JBootProperties;
import com.giffing.bucket4j.spring.boot.starter.context.properties.Bucket4JConfiguration;
import com.giffing.bucket4j.spring.boot.starter.context.properties.RateLimit;
import io.github.bucket4j.distributed.ExpirationAfterWriteStrategy;
import io.github.bucket4j.distributed.proxy.AbstractProxyManager;
import io.github.bucket4j.redis.lettuce.Bucket4jLettuce;
import io.github.bucket4j.redis.lettuce.cas.LettuceBasedProxyManager;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.codec.ByteArrayCodec;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
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
 * steps aside (its {@code backend=local} condition no longer matches).
 *
 * <h2>Redis connection — which {@code spring.data.redis.*} fields are honored</h2>
 *
 * <p>The built-in {@link #rateLimitRedisClient(Environment)} constructs a <strong>single-node</strong>
 * Lettuce {@link RedisClient} from <strong>exactly</strong> these standard properties:
 *
 * <ul>
 *   <li>{@code spring.data.redis.host} (default {@code localhost})
 *   <li>{@code spring.data.redis.port} (default {@code 6379})
 *   <li>{@code spring.data.redis.ssl.enabled}
 *   <li>{@code spring.data.redis.username} + {@code spring.data.redis.password}
 *   <li>{@code spring.data.redis.database}
 * </ul>
 *
 * <p><strong>Everything else is ignored</strong> by this client: {@code timeout},
 * {@code connect-timeout}, {@code client-name}, the {@code lettuce.pool.*} pool settings,
 * {@code spring.data.redis.sentinel.*}, and {@code spring.data.redis.cluster.*}. It also does
 * <strong>not</strong> reuse a Spring Data Redis {@code LettuceConnectionFactory} if one exists — left
 * to itself it would open a <em>second</em>, independent connection.
 *
 * <p>To use timeouts/pooling, Sentinel or Cluster, or to <strong>reuse an existing connection</strong>,
 * supply your own {@link RedisClient} {@code @Bean}: the factory here is {@link ConditionalOnMissingBean}
 * so a consumer-provided client always wins.
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

    /** Micrometer counter name incremented once per Redis rate-limit backend failure. */
    static final String REDIS_ERRORS_METRIC = "acme.ratelimit.redis.errors";

    private static final Logger log = LoggerFactory.getLogger(RedisRateLimitAutoConfiguration.class);

    /**
     * The raw Lettuce {@link RedisClient} used by the distributed ProxyManager. See the class javadoc
     * for the exact list of {@code spring.data.redis.*} fields honored (host/port/ssl/auth/database
     * only) and what is intentionally ignored. {@link ConditionalOnMissingBean} so a consumer-provided
     * client (sharing an existing Lettuce connection, or configured with timeouts/pool/Sentinel/Cluster)
     * always wins.
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
     *
     * <p>Validates {@code acme.ratelimit.redis.bucket-ttl} against the configured Bucket4j bandwidth
     * periods <strong>at startup</strong> (fail-fast): a ttl shorter than the longest window would
     * evict bucket state mid-window and silently reset the limit.
     */
    @Bean
    @ConditionalOnMissingBean
    public AbstractProxyManager<byte[]> rateLimitProxyManager(
            RedisClient rateLimitRedisClient, RateLimitProperties properties, Environment environment) {
        Duration bucketTtl = properties.getRedis().getBucketTtl();
        validateBucketTtl(bucketTtl, environment);
        return lettuceProxyManager(rateLimitRedisClient, bucketTtl);
    }

    /**
     * Bucket4j's servlet (synchronous) cache resolver, backed by the distributed Redis ProxyManager
     * and wired with the configured Redis-outage fail policy ({@code acme.ratelimit.redis.fail-open}).
     * With a {@link SyncCacheResolver} bean present, Bucket4j's servlet filter is created and every
     * bucket is resolved from Redis — so the limit is shared across replicas.
     *
     * <p>The {@code acme.ratelimit.redis.errors} Micrometer counter is gated on a {@link MeterRegistry}
     * being present ({@link ObjectProvider}); when none is configured the metric is a no-op.
     */
    @Bean
    @ConditionalOnMissingBean(SyncCacheResolver.class)
    public SyncCacheResolver bucket4jSyncCacheResolver(
            AbstractProxyManager<byte[]> rateLimitProxyManager,
            RateLimitProperties properties,
            ObjectProvider<MeterRegistry> meterRegistry) {
        boolean failOpen = properties.getRedis().isFailOpen();
        log.info(
                "acme-ratelimit Redis backend active; fail policy = {} (acme.ratelimit.redis.fail-open={})",
                failOpen ? "FAIL-OPEN (allow on Redis outage)" : "FAIL-CLOSED (HTTP 503 on Redis outage)",
                failOpen);
        Runnable errorCounter = buildErrorCounter(meterRegistry.getIfAvailable(), failOpen);
        return new RedisProxyManagerCacheResolver(
                rateLimitProxyManager, failOpen, properties.getRedis().getFailClosedRetryAfter(), errorCounter);
    }

    /**
     * Outermost servlet guard ({@link Ordered#HIGHEST_PRECEDENCE}) that translates a fail-closed Redis
     * outage into a clean HTTP 503. Transparent pass-through under the default fail-open policy.
     */
    @Bean
    @ConditionalOnMissingBean
    public FilterRegistrationBean<RedisRateLimitOutageFilter> redisRateLimitOutageFilter() {
        FilterRegistrationBean<RedisRateLimitOutageFilter> registration =
                new FilterRegistrationBean<>(new RedisRateLimitOutageFilter());
        registration.addUrlPatterns("/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        registration.setName("redisRateLimitOutageFilter");
        return registration;
    }

    private static Runnable buildErrorCounter(MeterRegistry registry, boolean failOpen) {
        if (registry == null) {
            return () -> {};
        }
        Counter counter = Counter.builder(REDIS_ERRORS_METRIC)
                .description("Rate-limit decisions that hit a Redis backend failure")
                .tag("outcome", failOpen ? "allowed" : "rejected")
                .register(registry);
        return counter::increment;
    }

    /**
     * Fail-fast validation that {@code bucket-ttl} is positive and {@code >=} the longest configured
     * Bucket4j bandwidth period. A too-small ttl evicts bucket keys before their window closes, which
     * silently resets the limit (a security bypass). The bandwidth periods are read from
     * {@code bucket4j.filters[].rate-limits[].bandwidths[]}; when none can be read the constraint is
     * logged loudly so it stays visible.
     */
    static void validateBucketTtl(Duration bucketTtl, Environment environment) {
        if (bucketTtl == null || bucketTtl.isZero() || bucketTtl.isNegative()) {
            throw new IllegalStateException(
                    "acme.ratelimit.redis.bucket-ttl must be a positive duration, but was " + bucketTtl);
        }
        Duration longestPeriod = longestBandwidthPeriod(environment);
        if (longestPeriod == null) {
            log.warn(
                    "acme-ratelimit Redis backend: bucket-ttl={} but no bucket4j bandwidth period could be "
                            + "read to validate it. Ensure bucket-ttl is >= your LONGEST bandwidth period, "
                            + "otherwise bucket keys are evicted mid-window and the limit silently resets.",
                    bucketTtl);
            return;
        }
        if (bucketTtl.compareTo(longestPeriod) < 0) {
            throw new IllegalStateException(String.format(
                    "acme.ratelimit.redis.bucket-ttl (%s) is shorter than the longest configured Bucket4j "
                            + "bandwidth period (%s). Redis would evict bucket state before the window closes "
                            + "and the rate limit would silently reset. Raise acme.ratelimit.redis.bucket-ttl "
                            + "to at least %s.",
                    bucketTtl, longestPeriod, longestPeriod));
        }
        log.info(
                "acme-ratelimit Redis backend: bucket-ttl={} validated >= longest bandwidth period {}.",
                bucketTtl,
                longestPeriod);
    }

    /**
     * Longest {@code bucket4j.filters[].rate-limits[].bandwidths[]} period, or {@code null} when no
     * bandwidths are configured/readable. Uses {@link java.time.temporal.ChronoUnit#getDuration()} so
     * estimated units (days/weeks) are handled without throwing.
     */
    private static Duration longestBandwidthPeriod(Environment environment) {
        Bucket4JBootProperties properties = Binder.get(environment)
                .bind("bucket4j", Bucket4JBootProperties.class)
                .orElseGet(Bucket4JBootProperties::new);
        Duration longest = null;
        for (Bucket4JConfiguration filter : properties.getFilters()) {
            for (RateLimit rateLimit : filter.getRateLimits()) {
                for (BandWidth bandwidth : rateLimit.getBandwidths()) {
                    if (bandwidth.getUnit() == null || bandwidth.getTime() <= 0) {
                        continue;
                    }
                    Duration period = bandwidth.getUnit().getDuration().multipliedBy(bandwidth.getTime());
                    if (longest == null || period.compareTo(longest) > 0) {
                        longest = period;
                    }
                }
            }
        }
        return longest;
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
