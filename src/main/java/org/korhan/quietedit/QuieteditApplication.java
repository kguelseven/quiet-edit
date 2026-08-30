package org.korhan.quietedit;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class QuieteditApplication {

    public static void main(String[] args) {
        SpringApplication.run(QuieteditApplication.class, args);
    }
}
