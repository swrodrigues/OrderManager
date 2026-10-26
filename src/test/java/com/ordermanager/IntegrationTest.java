package com.ordermanager;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/** Base class for tests that need the full Spring context and a real PostgreSQL. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
public abstract class IntegrationTest {

    /** The same pinned image production runs (docker/postgres/Dockerfile). */
    public static final String POSTGRES_IMAGE =
            "cgr.dev/chainguard/postgres@sha256:b32c0efe4c15bbc08fcd801f619a7d98d44f3f8eca3ec89efad4941a56e8741c";

    @ServiceConnection
    protected static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(DockerImageName.parse(POSTGRES_IMAGE).asCompatibleSubstituteFor("postgres"))
                    .withUrlParam("reWriteBatchedInserts", "true")
                    // the image's entrypoint already ends in "postgres"; pass only the server options
                    .withCommand("-c", "fsync=off");

    static {
        POSTGRES.start();
    }

    @Autowired protected MockMvc mvc;
    @Autowired protected JdbcTemplate jdbc;
    @Autowired protected TestData data;
}
