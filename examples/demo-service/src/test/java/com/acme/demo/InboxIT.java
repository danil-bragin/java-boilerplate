package com.acme.demo;

import static org.assertj.core.api.Assertions.assertThat;

import com.acme.messaging.Inbox;
import com.acme.test.PostgresTestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@SpringBootTest
@Import(PostgresTestcontainersConfiguration.class)
class InboxIT {

    @Autowired
    Inbox inbox;

    @Test
    void firstTimeIsTrueThenFalseForDuplicate() {
        assertThat(inbox.firstTime("test-listener", "msg-1")).isTrue();
        assertThat(inbox.firstTime("test-listener", "msg-1")).isFalse();
        assertThat(inbox.firstTime("test-listener", "msg-2")).isTrue();
    }
}
