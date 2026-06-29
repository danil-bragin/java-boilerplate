# acme-httpclient

Thin starter. Pulls runtime dependencies (spring-web `RestClient` + interface-client support, the Micrometer observation API) and activates `acme-httpclient-spring-boot-autoconfigure` on the classpath. Add `acme-security` for token relay and `acme-resilience` for the resilience decorator — both are optional and the autoconfig backs off when absent. See that module's README for what it configures.
