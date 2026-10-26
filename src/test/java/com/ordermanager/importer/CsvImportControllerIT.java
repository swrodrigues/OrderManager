package com.ordermanager.importer;

import com.ordermanager.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.RequestBuilder;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WithMockUser(username = "op", roles = "OPERATOR")
class CsvImportControllerIT extends IntegrationTest {

    static final String GOOD = String.join("\n",
            CsvImportServiceIT.HEADER,
            "EXT-1,Zoë Müller,Kerkstraat 1,,A,2",
            "EXT-1,Zoë Müller,Kerkstraat 1,,B,1",
            "EXT-2,Jan Peeters,Markt 3,,ZZZ,1") + "\n";

    @BeforeEach
    void setUp() {
        data.truncateAll();
        data.sku("A", 100);
        data.sku("B", 100);
    }

    @Test
    void pageRenders() throws Exception {
        mvc.perform(get("/import"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("external_ref,ship_to_name,ship_to_address,ship_to_phone,sku_code,qty")));
    }

    @Test
    void uploadShowsSummary() throws Exception {
        mvc.perform(upload("orders.csv", "text/csv", GOOD))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Imported 1 of 2 orders")))
                .andExpect(content().string(containsString("Line 4")))
                .andExpect(content().string(containsString("Unknown SKU: ZZZ")));
    }

    @Test
    void excelContentTypeAccepted() throws Exception {
        mvc.perform(upload("orders.csv", "application/vnd.ms-excel", GOOD))
                .andExpect(status().isOk());
    }

    @Test
    void nonCsvRejected() throws Exception {
        mvc.perform(upload("orders.xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", GOOD))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(content().string(containsString("Please upload a .csv file")));
    }

    @Test
    void semicolonFileShowsHeaderHint() throws Exception {
        mvc.perform(upload("orders.csv", "text/csv", "external_ref;ship_to_name;ship_to_address;ship_to_phone;sku_code;qty\nE;n;a;;A;1\n"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(content().string(containsString("expected comma-separated header")));
    }

    @Test
    void duplicateFileGives409() throws Exception {
        mvc.perform(upload("orders.csv", "text/csv", GOOD)).andExpect(status().isOk());
        mvc.perform(upload("orders.csv", "text/csv", GOOD))
                .andExpect(status().isConflict())
                .andExpect(content().string(containsString("This file was already imported")));
    }

    /** Review finding 7: uploads (plaintext addresses) must stay in memory, never spooled to a temp file. */
    @Test
    void uploadsAreNeverWrittenToDisk(@org.springframework.beans.factory.annotation.Autowired
                                      jakarta.servlet.MultipartConfigElement multipart) {
        org.assertj.core.api.Assertions.assertThat(multipart.getFileSizeThreshold())
                .isGreaterThanOrEqualTo((int) multipart.getMaxRequestSize());
    }

    /** Review finding 5: htmx must swap the server's 400/403/500 messages (they are retargeted to #flash). */
    @Test
    void htmxConfigShowsServerErrorMessages() throws Exception {
        mvc.perform(get("/import"))
                .andExpect(content().string(containsString("(400|403|404|409|422|500)")));
    }

    @Test
    void sampleCsvIsServed() throws Exception {
        mvc.perform(get("/sample-orders.csv"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(CsvImportServiceIT.HEADER)));
    }

    private static RequestBuilder upload(String name, String type, String body) {
        return multipart("/import")
                .file(new MockMultipartFile("file", name, type, body.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                .header("HX-Request", "true")
                .with(csrf());
    }
}
