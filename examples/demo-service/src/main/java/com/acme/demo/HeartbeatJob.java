package com.acme.demo;

import java.util.concurrent.atomic.AtomicInteger;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Demo scheduled job whose runs are deduped across instances by ShedLock. */
@Component
public class HeartbeatJob {

    private final AtomicInteger runs = new AtomicInteger();

    @Scheduled(fixedRate = 200)
    @SchedulerLock(name = "heartbeat", lockAtMostFor = "PT30S", lockAtLeastFor = "PT0S")
    public void beat() {
        runs.incrementAndGet();
    }

    public int runs() {
        return runs.get();
    }
}
