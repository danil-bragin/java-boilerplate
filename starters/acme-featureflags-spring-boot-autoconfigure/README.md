# acme-featureflags-spring-boot-autoconfigure

Auto-configures the OpenFeature Java SDK: wires a singleton `Client` over an overridable `FeatureProvider`. Ships a no-op default so the SDK is usable out of the box; supply a provider bean to drive real flag values.

## What it configures
- `FeatureProvider featureProvider` — a `NoOpProvider` by default, registered `@ConditionalOnMissingBean` so a consumer's provider bean takes precedence.
- `Client featureFlagsClient` — registers the resolved provider on the JVM-wide `OpenFeatureAPI` singleton via `setProviderAndWait(...)` and returns `OpenFeatureAPI.getClient()`. Gated `@ConditionalOnClass(OpenFeatureAPI.class)`.

Note: `OpenFeatureAPI` is a process-wide singleton — wiring the client mutates global state, which matters when multiple Spring contexts coexist (e.g. tests).

## Key properties
None.

## Usage
```kotlin
implementation("acme-bank:acme-featureflags-spring-boot-starter")
```
On the classpath this activates `FeatureFlagsAutoConfiguration`; inject `dev.openfeature.sdk.Client` to evaluate flags, and define your own `FeatureProvider` bean to replace the no-op default.

## See also
- ADR-0007 Utility starters: cache (Caffeine), resilience (Resilience4j), feature flags (OpenFeature)
- root README
