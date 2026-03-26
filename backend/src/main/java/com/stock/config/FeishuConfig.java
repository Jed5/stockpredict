package com.stock.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "stock.feishu")
public class FeishuConfig {
    private boolean enabled;
    private String webhook;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getWebhook() { return webhook; }
    public void setWebhook(String webhook) { this.webhook = webhook; }
}
