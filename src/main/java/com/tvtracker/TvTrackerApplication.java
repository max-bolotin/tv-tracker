package com.tvtracker;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.time.Clock;
import java.time.ZoneId;

@SpringBootApplication
@EnableScheduling
public class TvTrackerApplication {
    @Bean
    Clock applicationClock(@Value("${app.timezone:Europe/Berlin}") String timezone) {
        return Clock.system(ZoneId.of(timezone));
    }

    public static void main(String[] args) {
        SpringApplication.run(TvTrackerApplication.class, args);
    }
}
