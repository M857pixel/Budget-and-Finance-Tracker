package com.pocketledger.config;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.CertificateFactory;
import static org.junit.jupiter.api.Assertions.*;

class SupabaseCertificateConfigurationTest {
    @Test
    void replacesMachineSpecificPathWithReadableCertificateWithoutConnecting() throws Exception {
        for (String suffix : new String[]{"", "&sslrootcert=C%3A%5Cmissing%5Croot.crt"}) {
            try (var source = new HikariDataSource()) {
                source.setJdbcUrl("jdbc:postgresql://aws-0-us-west-2.pooler.supabase.com:5432/postgres?sslmode=verify-full" + suffix);
                SupabaseCertificateConfiguration.supabaseCertificate().postProcessBeforeInitialization(source, "dataSource");
                String query = URI.create(source.getJdbcUrl().substring(5)).getRawQuery();
                assertTrue(query.contains("sslmode=verify-full"));
                String encoded = query.substring(query.indexOf("sslrootcert=") + "sslrootcert=".length());
                Path certificate = Path.of(URLDecoder.decode(encoded, StandardCharsets.UTF_8));
                assertTrue(certificate.isAbsolute());
                try (var input = Files.newInputStream(certificate)) {
                    assertNotNull(CertificateFactory.getInstance("X.509").generateCertificate(input));
                } finally {
                    Files.delete(certificate);
                }
            }
        }
    }

    @Test
    void leavesOtherDatabasesAlone() {
        try (var source = new HikariDataSource()) {
            String url = "jdbc:h2:mem:test";
            source.setJdbcUrl(url);
            SupabaseCertificateConfiguration.supabaseCertificate().postProcessBeforeInitialization(source, "dataSource");
            assertEquals(url, source.getJdbcUrl());
        }
    }
}
