package com.pocketledger.config;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

@Configuration(proxyBeanMethods = false)
public class SupabaseCertificateConfiguration {
    @Bean
    static BeanPostProcessor supabaseCertificate() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessBeforeInitialization(Object bean, String beanName) {
                if (bean instanceof HikariDataSource source && isSupabase(source.getJdbcUrl())) {
                    // pgJDBC needs a real file, including when resources live inside a JAR.
                    try (var input = new ClassPathResource("certs/supabase-ca.crt").getInputStream()) {
                        var certificate = Files.createTempFile("pocketledger-supabase-ca-", ".crt");
                        certificate.toFile().deleteOnExit();
                        Files.copy(input, certificate, StandardCopyOption.REPLACE_EXISTING);
                        String path = URLEncoder.encode(certificate.toString(), StandardCharsets.UTF_8);
                        String url = source.getJdbcUrl();
                        // URL parameters take precedence over Hikari data source properties.
                        url = url.replaceAll("([?&])sslrootcert=[^&]*", "$1sslrootcert=" + path);
                        if (!url.contains("sslrootcert=")) {
                            url += (url.contains("?") ? "&" : "?") + "sslrootcert=" + path;
                        }
                        source.setJdbcUrl(url);
                    } catch (IOException e) {
                        throw new IllegalStateException("Could not load the bundled Supabase CA certificate", e);
                    }
                }
                return bean;
            }
        };
    }

    private static boolean isSupabase(String url) {
        if (url == null || !url.startsWith("jdbc:postgresql://")) return false;
        String host = URI.create(url.substring(5)).getHost();
        return host != null && (host.endsWith(".supabase.com") || host.endsWith(".supabase.co"));
    }
}
