package com.mantis;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
@EnableAsync
public class MantisApplication {
    public static void main(String[] args) {
        SpringApplication.run(MantisApplication.class, args);
    }
}
