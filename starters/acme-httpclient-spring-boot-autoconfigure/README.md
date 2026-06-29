# acme-httpclient-spring-boot-autoconfigure

Auto-configures one opinionated way to build typed **outbound** HTTP clients, so services stop hand-rolling `RestClient` call sites. You define a declarative `@HttpExchange` interface and ask for a proxy; the starter bakes in sane timeouts, Micrometer **observation** (distributed tracing + metrics for outbound calls), optional OAuth2 **token relay**, and a Resilience4j **decorator** over the `acme-resilience` presets. Every bean is `@ConditionalOnMissingBean`, so consumers can override any piece.

## What it configures
- `RestClient.Builder acmeRestClientBuilder` — the shared builder. Applies connect/read timeouts from properties (via `ClientHttpRequestFactorySettings`), wires the `ObservationRegistry` when one is present (outbound calls become traced + metered observations), and applies all `RestClientCustomizer` beans. Gated `@ConditionalOnClass(RestClient.class)`, registered `@ConditionalOnMissingBean`.
- `HttpClients httpClients` — factory that turns a `@HttpExchange` interface into a proxy via `HttpServiceProxyFactory` + `RestClientAdapter`, backed by the shared builder. `@ConditionalOnMissingBean`.
- `BearerTokenRelayInterceptor` + a `RestClientCustomizer` — copies the current request's `Authorization: Bearer …` onto outbound calls. Active only when `acme.httpclient.token-relay.enabled=true` **and** the resource-server JWT types are on the classpath (`@ConditionalOnClass(JwtAuthenticationToken.class)`). This is the acme-bank gateway's proven token-relay pattern.
- `ResilienceDecorator httpClientResilienceDecorator` — wraps a blocking call with a Resilience4j `CircuitBreaker` + `Retry` resolved by instance name, reusing the registries brought by `acme-resilience`. Active only when Resilience4j is on the classpath (`@ConditionalOnClass(CircuitBreakerRegistry.class)`).

## Key properties
| Property | Default | Purpose |
|---|---|---|
| `acme.httpclient.connect-timeout` | `2s` | Connect timeout on the shared `RestClient.Builder` |
| `acme.httpclient.read-timeout` | `10s` | Read (response) timeout on the shared `RestClient.Builder` |
| `acme.httpclient.token-relay.enabled` | `false` | Relay the caller's bearer token onto outbound calls (requires the resource-server types on the classpath) |
| `acme.httpclient.resilience.default-instance` | `httpclient` | Resilience4j instance name used by `ResilienceDecorator.call(Supplier)` when none is given |

## Usage

**1. Declare a typed client interface** (`@HttpExchange` / `@GetExchange` / `@PostExchange`):
```java
public interface CatalogApi {
    @GetExchange("/products/{id}")
    Product product(@PathVariable String id);
}
```

**2. Get a proxy from the injected `HttpClients` factory:**
```java
@Configuration
class Clients {
    @Bean
    CatalogApi catalogApi(HttpClients httpClients) {
        return httpClients.create(CatalogApi.class, "https://catalog.internal");
    }
}
```
The proxy inherits the configured timeouts, observation wiring, and (when enabled) token relay automatically.

**3. (Optional) token relay** — add `acme-security` (or any resource-server) to the classpath and set:
```yaml
acme:
  httpclient:
    token-relay:
      enabled: true
```
Now the caller's JWT is forwarded to downstream resource servers (no more 401-masked-as-503).

**4. (Optional) resilience** — add `acme-resilience` and either wrap calls programmatically:
```java
Product p = resilienceDecorator.call("catalog", () -> catalogApi.product("p-1"));
```
…with instances tuned under `resilience4j.*`; or, for full `CircuitBreaker` + `Retry` + `TimeLimiter`, annotate a delegating `@Component` method (`@CircuitBreaker`/`@Retry`/`@TimeLimiter`) exactly as the acme-bank gateway does.

## See also
- ADR-0033 Declarative HTTP interface clients with resilience, token relay, and observation
- `acme-security` (bearer token on the incoming request), `acme-resilience` (Resilience4j presets), `acme-observability` (Micrometer/OTel registry)
- root README
