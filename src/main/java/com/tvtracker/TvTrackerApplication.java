package com.tvtracker;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class TvTrackerApplication {
    public static void main(String[] args) {
        SpringApplication.run(TvTrackerApplication.class, args);
    }
}
