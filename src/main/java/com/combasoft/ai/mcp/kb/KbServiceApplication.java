package com.combasoft.ai.mcp.kb;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

@EnableAsync
@SpringBootApplication()
public class KbServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(KbServiceApplication.class, args);
    }
}
