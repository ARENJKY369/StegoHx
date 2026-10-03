package com.stegohx.backend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * StegoHX Engine - steganography operations service.
 *
 * Exposes hide / extract / scan / clean / evolve operations over REST,
 * delegating detection to the Python analyzer service and persisting scan
 * history for reporting. Authorized security research use only - see
 * docs/SECURITY.md.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class StegoHxApplication {

    public static void main(String[] args) {
        SpringApplication.run(StegoHxApplication.class, args);
    }
}
