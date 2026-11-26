package com.research.platform;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;


@SpringBootApplication
@MapperScan({
        "com.research.platform.modules.*.mapper",
        "com.research.platform.modules.admin.audit.mapper"
})
public class ResearchPlatformApplication {

    public static void main(String[] args) {
        SpringApplication.run(ResearchPlatformApplication.class, args);
    }

}
