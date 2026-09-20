package com.careerai.backend.admin;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import java.util.HashSet;
import java.util.Set;

@Getter
@Setter
@ConfigurationProperties(prefix = "careerai.admin")
public class AdminProperties {
    private boolean enabled;
    private Set<Long> telegramUserIds = new HashSet<>();
    private long authMaxAgeSeconds = 3600;
    private String publicUrl = "";
}
