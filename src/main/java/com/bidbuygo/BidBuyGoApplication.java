package com.bidbuygo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

// Authentication is JWT-only, so the default in-memory user (and its generated password) is not wanted.
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
@ConfigurationPropertiesScan
public class BidBuyGoApplication {

    public static void main(String[] args) {
        SpringApplication.run(BidBuyGoApplication.class, args);
    }
}
