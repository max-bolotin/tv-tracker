package com.tvtracker;

import java.time.ZoneId;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ApplicationClockTest {
    @Test
    void applicationClockUsesConfiguredTimezone() {
        assertThat(new TvTrackerApplication().applicationClock("Asia/Tokyo").getZone())
                .isEqualTo(ZoneId.of("Asia/Tokyo"));
    }

    @Test
    void applicationClockDefaultsToBerlinTimezone() {
        assertThat(new TvTrackerApplication().applicationClock("Europe/Berlin").getZone())
                .isEqualTo(ZoneId.of("Europe/Berlin"));
    }
}
