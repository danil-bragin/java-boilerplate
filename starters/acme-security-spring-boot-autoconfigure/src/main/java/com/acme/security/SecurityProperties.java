package com.acme.security;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Configuration for the security starter. */
@ConfigurationProperties(prefix = "acme.security")
public class SecurityProperties {

    /** Ant patterns permitted without authentication. Defaults to actuator health + info. */
    private List<String> permitPaths = List.of("/actuator/health/**", "/actuator/info");

    public List<String> getPermitPaths() {
        return permitPaths;
    }

    public void setPermitPaths(List<String> permitPaths) {
        this.permitPaths = permitPaths;
    }
}
