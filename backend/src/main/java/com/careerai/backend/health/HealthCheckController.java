package com.careerai.backend.health;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Проверка работы локального Backend сервера.
 */

@RestController
public class HealthCheckController {

    @GetMapping("/api/health")
    public String health() {
        return "CareerAI Backend is running";
    }
}
