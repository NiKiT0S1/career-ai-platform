package com.careerai.backend.config;

import org.flywaydb.core.Flyway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

/**
 * Запускает Flyway-миграции базы данных при старте backend-приложения.
 *
 * В этом проекте Flyway отвечает за создание и обновление структуры PostgreSQL
 * через SQL-файлы из папки {@code src/main/resources/db/migration}.
 * Такой подход делает структуру БД предсказуемой и версионируемой.
 */

@Configuration
public class DatabaseMigrationConfig {

    private static final Logger log = LoggerFactory.getLogger(DatabaseMigrationConfig.class);

    @Bean(initMethod = "migrate")
    public Flyway flyway(DataSource dataSource) {
        log.info("Initializing Flyway manually");

        return Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .baselineOnMigrate(true)
                .load();
    }
}
