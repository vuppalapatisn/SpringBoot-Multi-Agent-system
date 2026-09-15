package io.github.vuppalapatisn.agentic.agentloop.testsupport;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

/** A clock you can move, so the wall-clock budget is testable. */
public class MutableClock extends Clock {

    private Instant now;
    private final ZoneId zone;

    public MutableClock(Instant now) {
        this(now, ZoneId.of("UTC"));
    }

    private MutableClock(Instant now, ZoneId zone) {
        this.now = now;
        this.zone = zone;
    }

    public void advance(Duration amount) {
        now = now.plus(amount);
    }

    public void set(Instant instant) {
        this.now = instant;
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    @Override
    public Clock withZone(ZoneId newZone) {
        return new MutableClock(now, newZone);
    }

    @Override
    public Instant instant() {
        return now;
    }
}
