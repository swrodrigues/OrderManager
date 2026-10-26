package com.ordermanager.order;

import com.ordermanager.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WithMockUser(username = "op", roles = "OPERATOR")
class OrderControllerIT extends IntegrationTest {

    @Autowired OrderService orders;
    @Autowired OrderQueryService query;
    long skuA;

    @BeforeEach
    void setUp() {
        data.truncateAll();
        skuA = data.sku("A", 10);
    }

    @Test
    void rootRedirectsToOrders() throws Exception {
        mvc.perform(get("/")).andExpect(redirectedUrl("/orders"));
    }

    @Test
    void listRendersAndHtmxReturnsRowsOnly() throws Exception {
        long id = data.order("EXT-1", Map.of("A", 1));
        String orderNo = orders.get(id).getOrderNo();

        mvc.perform(get("/orders"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("<html")))
                .andExpect(content().string(containsString(orderNo)));

        mvc.perform(get("/orders").header("HX-Request", "true"))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("<html"))))
                .andExpect(content().string(containsString(orderNo)));
    }

    @Test
    void statusFilterApplies() throws Exception {
        long a = data.order("EXT-1", Map.of("A", 1));
        long b = data.order("EXT-2", Map.of("A", 1));
        orders.transition(a, OrderStatus.ALLOCATED, 0);
        mvc.perform(get("/orders").param("status", "ALLOCATED").header("HX-Request", "true"))
                .andExpect(content().string(containsString(orders.get(a).getOrderNo())))
                .andExpect(content().string(not(containsString(orders.get(b).getOrderNo()))));
    }

    /** htmx sends the filter form's fields on top of the Load-more URL's own (review finding C1). */
    @Test
    void loadMoreSurvivesDuplicatedFilterParams() throws Exception {
        long first = data.order("EXT-1", Map.of("A", 1));
        long second = data.order("EXT-2", Map.of("A", 1));
        String after = query.page(new OrderFilter(null, null), null, 1).next().encode();
        String older = orders.get(first).getOrderNo();
        assertThat(orders.get(second).getId()).isNotNull();

        mvc.perform(get("/orders").header("HX-Request", "true")
                        .param("status", "").param("q", "").param("after", after)
                        .param("status", "").param("q", ""))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(older)))
                .andExpect(content().string(not(containsString("No orders match."))));
    }

    @Test
    void rowsDoNotInheritTheFilterInclude() throws Exception {
        mvc.perform(get("/orders"))
                .andExpect(content().string(not(containsString("id=\"order-rows\" hx-get"))));
    }

    @Test
    void transitionReturnsUpdatedRow() throws Exception {
        long id = data.order("EXT-1", Map.of("A", 3));
        mvc.perform(transition(id, "ALLOCATED", 0, "row"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("ALLOCATED")))
                .andExpect(content().string(not(containsString("<html"))));
        assertThat(orders.get(id).getStatus()).isEqualTo(OrderStatus.ALLOCATED);
    }

    @Test
    void rowAfterAnActionOffersUndo() throws Exception {
        long id = data.order("EXT-1", Map.of("A", 3));
        mvc.perform(transition(id, "ALLOCATED", 0, "row"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("/orders/" + id + "/undo")))
                .andExpect(content().string(containsString("Undo")));
    }

    @Test
    void undoFromARowReturnsTheRowAndAMessage() throws Exception {
        long id = data.order("EXT-1", Map.of("A", 3));
        mvc.perform(transition(id, "ALLOCATED", 0, "row")).andExpect(status().isOk());

        mvc.perform(undo(id, orders.get(id).getVersion(), "row"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("s-NEW")))
                .andExpect(content().string(not(containsString("/undo"))))
                .andExpect(content().string(containsString("hx-swap-oob")))
                .andExpect(content().string(containsString("Undone: ALLOCATED → NEW")));
        assertThat(jdbc.queryForObject("select reserved from sku where id=?", Integer.class, skuA)).isZero();
    }

    @Test
    void undoThatIsNotAllowedShowsWhy() throws Exception {
        long id = data.order("EXT-1", Map.of("A", 3));
        mvc.perform(undo(id, 0, "row"))
                .andExpect(status().isConflict())
                .andExpect(header().string("HX-Retarget", "#flash"))
                .andExpect(content().string(containsString("There is nothing to undo on this order")));
    }

    @Test
    void detailPageOffersUndoOnlyWhileAllowed() throws Exception {
        long id = data.order("EXT-1", Map.of("A", 3));
        mvc.perform(get("/orders/{id}", id)).andExpect(content().string(not(containsString("/undo"))));
        mvc.perform(transition(id, "ALLOCATED", 0, "detail")).andExpect(status().isOk())
                .andExpect(content().string(containsString("/orders/" + id + "/undo")));
        mvc.perform(get("/orders/{id}", id)).andExpect(content().string(containsString("/orders/" + id + "/undo")));

        mvc.perform(undo(id, orders.get(id).getVersion(), "detail"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("id=\"order-panel\"")))
                .andExpect(content().string(containsString("Undone: ALLOCATED → NEW")));
    }

    @Test
    void transitionFromDetailReturnsPanel() throws Exception {
        long id = data.order("EXT-1", Map.of("A", 3));
        mvc.perform(transition(id, "ALLOCATED", 0, "detail"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("id=\"order-panel\"")));
    }

    @Test
    void staleVersionGives409Message() throws Exception {
        long id = data.order("EXT-1", Map.of("A", 3));
        mvc.perform(transition(id, "ALLOCATED", 0, "row")).andExpect(status().isOk());
        mvc.perform(transition(id, "ALLOCATED", 0, "row"))
                .andExpect(status().isConflict())
                .andExpect(header().string("HX-Retarget", "#flash"))
                .andExpect(content().string(containsString("This order changed — reload")));
        assertThat(jdbc.queryForObject("select reserved from sku where id=?", Integer.class, skuA)).isEqualTo(3);
    }

    @Test
    void invalidTransitionGives409() throws Exception {
        long id = data.order("EXT-1", Map.of("A", 3));
        mvc.perform(transition(id, "SHIPPED", 0, "row"))
                .andExpect(status().isConflict())
                .andExpect(content().string(containsString("Order cannot move from NEW to SHIPPED")));
    }

    @Test
    void shortStockGives422WithSkuList() throws Exception {
        long id = data.order("EXT-1", Map.of("A", 30));
        mvc.perform(transition(id, "ALLOCATED", 0, "row"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(content().string(containsString("Not enough stock: A (need 30, have 10)")));
    }

    @Test
    void missingOrderGives404() throws Exception {
        mvc.perform(get("/orders/999999")).andExpect(status().isNotFound());
    }

    @Test
    void bulkAllocateReportsCountsAndTriggersRefresh() throws Exception {
        data.order("EXT-1", Map.of("A", 4));
        data.order("EXT-2", Map.of("A", 4));
        data.order("EXT-3", Map.of("A", 4));
        mvc.perform(post("/orders/bulk-allocate").header("HX-Request", "true").with(csrf()))
                .andExpect(status().isOk())
                .andExpect(header().string("HX-Trigger", "orders-changed"))
                .andExpect(content().string(containsString("Allocated 2, short of stock 1")));
    }

    @Test
    void createOrderRedirectsToDetail() throws Exception {
        MvcResult r = mvc.perform(post("/orders").with(csrf())
                        .param("externalRef", "WEB-1")
                        .param("shipToName", "Zoë Müller")
                        .param("shipToAddress", "Kerkstraat 1, Gent")
                        .param("shipToPhone", "+32 9 123 45 67")
                        .param("lines[0].skuCode", "A")
                        .param("lines[0].qty", "2")
                        .param("lines[1].skuCode", "")
                        .param("lines[1].qty", ""))
                .andExpect(status().is3xxRedirection())
                .andReturn();
        assertThat(r.getResponse().getRedirectedUrl()).matches("/orders/\\d+");
        assertThat(jdbc.queryForObject("select count(*) from order_line", Integer.class)).isEqualTo(1);
    }

    @Test
    void createOrderValidationErrorRerendersForm() throws Exception {
        mvc.perform(post("/orders").with(csrf())
                        .param("shipToName", " ")
                        .param("shipToAddress", "Kerkstraat 1")
                        .param("lines[0].skuCode", "A")
                        .param("lines[0].qty", "2"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(content().string(containsString("must not be blank")))
                .andExpect(content().string(containsString("Kerkstraat 1")));
    }

    @Test
    void createOrderUnknownSkuShowsMessage() throws Exception {
        mvc.perform(post("/orders").with(csrf())
                        .param("shipToName", "X")
                        .param("shipToAddress", "Kerkstraat 1")
                        .param("lines[0].skuCode", "NOPE")
                        .param("lines[0].qty", "2"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(content().string(containsString("Unknown SKU: NOPE")));
    }

    @Test
    void createOrderDuplicateExternalRefShowsMessage() throws Exception {
        data.order("DUP-1", Map.of("A", 1));
        mvc.perform(post("/orders").with(csrf())
                        .param("externalRef", "DUP-1")
                        .param("shipToName", "X")
                        .param("shipToAddress", "Kerkstraat 1")
                        .param("lines[0].skuCode", "A")
                        .param("lines[0].qty", "1"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(content().string(containsString("External reference DUP-1 already exists")));
    }

    @Test
    void detailShowsDecryptedAddressAndAudit() throws Exception {
        long id = data.order("EXT-1", Map.of("A", 1));
        mvc.perform(get("/orders/{id}", id))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Église 12, Sint-Truiden")))
                .andExpect(content().string(containsString("CREATE")));
    }

    /** Regression: the Undo response block was rendered on every order page, showing the order twice. */
    @Test
    void orderPageShowsTheOrderOnce() throws Exception {
        long id = data.order("EXT-1", Map.of("A", 1));
        String html = mvc.perform(get("/orders/{id}", id)).andReturn().getResponse().getContentAsString();
        assertThat(html.split("id=\"order-panel\"", -1)).hasSize(2);
        assertThat(html.split("id=\"flash\"", -1)).hasSize(2);
        assertThat(html).doesNotContain("hx-swap-oob");
    }

    @Test
    void footerShowsRenderTime() throws Exception {
        mvc.perform(get("/orders"))
                .andExpect(content().string(matchesPattern("(?s).*rendered in \\d+ ms.*")));
    }

    @Test
    void unexpectedErrorShowsRefNotDetails() throws Exception {
        mvc.perform(get("/test/boom"))
                .andExpect(status().isInternalServerError())
                .andExpect(content().string(containsString("Reference")))
                .andExpect(content().string(not(containsString("secret internal detail"))))
                .andExpect(content().string(not(containsString("Exception"))));
    }

    @Test
    void malformedIdGives400() throws Exception {
        mvc.perform(get("/orders/abc")).andExpect(status().isBadRequest());
    }

    private static org.springframework.test.web.servlet.RequestBuilder undo(long id, long version, String view) {
        return post("/orders/{id}/undo", id).header("HX-Request", "true").with(csrf())
                .param("version", String.valueOf(version)).param("view", view);
    }

    private static org.springframework.test.web.servlet.RequestBuilder transition(long id, String to, long version, String view) {
        return post("/orders/{id}/transition", id).header("HX-Request", "true").with(csrf())
                .param("to", to).param("version", String.valueOf(version)).param("view", view);
    }
}
