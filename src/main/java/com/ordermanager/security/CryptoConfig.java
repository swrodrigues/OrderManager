package com.ordermanager.security;

import com.ordermanager.common.AppProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class CryptoConfig {

    /** Fails startup when APP_ENC_KEY is missing or not 32 bytes. */
    @Bean
    public CryptoService cryptoService(AppProperties props) {
        return new CryptoService(props.encKey());
    }
}
