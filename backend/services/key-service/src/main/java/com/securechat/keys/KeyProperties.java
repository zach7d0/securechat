package com.securechat.keys;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("securechat.keys")
public record KeyProperties(int maxAvailablePrekeys, int maxAvailableKeyPackages, int prekeyLowThreshold,
        int keyPackageLowThreshold, int claimsPerMinute) {
}
