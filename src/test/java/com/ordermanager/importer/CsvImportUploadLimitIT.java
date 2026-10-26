package com.ordermanager.importer;

import com.ordermanager.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.io.ByteArrayOutputStream;
import java.net.CookieManager;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MockMvc does not enforce multipart size limits, so this test uploads a 6 MB file to a real server,
 * logged in through the real login form.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CsvImportUploadLimitIT extends IntegrationTest {

    private static final Pattern CSRF = Pattern.compile("name=\"_csrf\" value=\"([^\"]+)\"");

    @LocalServerPort int port;

    @Test
    void oversizeFileShowsFriendlyError() throws Exception {
        HttpClient http = HttpClient.newBuilder().cookieHandler(new CookieManager())
                .followRedirects(HttpClient.Redirect.NEVER).build();

        String loginToken = csrf(get(http, "/login"));
        String form = "username=admin&password=" + URLEncoder.encode("test-admin-password-123", StandardCharsets.UTF_8)
                + "&_csrf=" + URLEncoder.encode(loginToken, StandardCharsets.UTF_8);
        HttpResponse<String> login = http.send(HttpRequest.newBuilder(uri("/login"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form)).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(login.headers().firstValue("Location")).hasValueSatisfying(l -> assertThat(l).endsWith("/orders"));

        String token = csrf(get(http, "/import"));
        String boundary = "----om" + System.nanoTime();
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.writeBytes(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"big.csv\"\r\n"
                + "Content-Type: text/csv\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        body.writeBytes(new byte[6 * 1024 * 1024]);
        body.writeBytes(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));

        HttpResponse<String> res = http.send(HttpRequest.newBuilder(uri("/import"))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .header("X-CSRF-TOKEN", token)
                .header("HX-Request", "true")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray())).build(), HttpResponse.BodyHandlers.ofString());

        assertThat(res.statusCode()).isEqualTo(422);
        assertThat(res.body()).contains("File is larger than 5 MB").doesNotContain("Whitelabel");
    }

    private String get(HttpClient http, String path) throws Exception {
        return http.send(HttpRequest.newBuilder(uri(path)).GET().build(), HttpResponse.BodyHandlers.ofString()).body();
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    private static String csrf(String html) {
        Matcher m = CSRF.matcher(html);
        assertThat(m.find()).as("csrf token in page").isTrue();
        return m.group(1);
    }
}
