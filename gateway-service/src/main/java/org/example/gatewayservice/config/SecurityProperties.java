package org.example.gatewayservice.config;

import java.util.List;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;


@Data
@Component
@ConfigurationProperties(prefix = "auth")
public class SecurityProperties {
    private List<String> whitelist;
}
