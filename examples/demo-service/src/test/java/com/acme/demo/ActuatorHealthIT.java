package com.acme.demo;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.acme.test.PostgresTestcontainersConfiguration;
import io.micrometer.tracing.Tracer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = "management.tracing.sampling.probability=0.0")
@AutoConfigureMockMvc
@Import(PostgresTestcontainersConfiguration.class)
class ActuatorHealthIT {

    @Autowired
    MockMvc mvc;

    @Autowired
    ApplicationContext ctx;

    @Test
    void livenessProbeIsUp() throws Exception {
        mvc.perform(get("/actuator/health/liveness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void readinessProbeIsUp() throws Exception {
        mvc.perform(get("/actuator/health/readiness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void tracerBeanIsConfigured() {
        org.assertj.core.api.Assertions.assertThat(ctx.getBean(Tracer.class)).isNotNull();
    }
}
