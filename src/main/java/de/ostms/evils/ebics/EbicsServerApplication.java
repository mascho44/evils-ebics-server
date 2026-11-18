package de.ostms.evils.ebics;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Entry point of the EVILS EBICS server. {@code @EnableScheduling} activates the background jobs: the
 * daily test statements, the processing of due scheduled payments and the optional monthly fee closing.
 */
@SpringBootApplication
@EnableScheduling
public class EbicsServerApplication {
    public static void main(String[] args) {
        SpringApplication.run(EbicsServerApplication.class, args);
    }
}
