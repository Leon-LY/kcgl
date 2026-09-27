package com.kcgl;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * 应用入口。包结构约定见 docs/01 四节：package-by-feature，模块内分层。
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class KcglApplication {

    public static void main(String[] args) {
        SpringApplication.run(KcglApplication.class, args);
    }
}
