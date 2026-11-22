package com.securechat.keys;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class KeyApplication {
    public static void main(String[] args) {
        SpringApplication.run(KeyApplication.class, args);
    }
}
