package com.research.platform.config;

import org.springframework.boot.CommandLineRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class DatabaseTestRunner implements CommandLineRunner {

    private final JdbcTemplate jdbcTemplate;

    public DatabaseTestRunner(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void run(String... args) {

        Integer result = jdbcTemplate.queryForObject(
                "SELECT 1",
                Integer.class
        );

        if (result != null && result == 1) {
            System.out.println("==============================");
            System.out.println("MySQL 数据库连接成功！");
            System.out.println("==============================");
        }
    }
}