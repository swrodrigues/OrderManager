package com.ordermanager;

import com.ordermanager.order.NewOrderCommand;
import com.ordermanager.order.OrderService;
import com.ordermanager.security.AppUser;
import com.ordermanager.security.AppUserRepository;
import com.ordermanager.security.Role;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/** Test fixtures shared by integration tests. */
@Component
public class TestData {

    private final JdbcTemplate jdbc;
    private final AppUserRepository users;
    private final PasswordEncoder encoder;
    private final OrderService orders;

    public TestData(JdbcTemplate jdbc, AppUserRepository users, PasswordEncoder encoder, OrderService orders) {
        this.jdbc = jdbc;
        this.users = users;
        this.encoder = encoder;
        this.orders = orders;
    }

    public void truncateAll() {
        jdbc.execute("TRUNCATE order_line, customer_order, sku, audit_log, import_batch RESTART IDENTITY CASCADE");
    }

    /** Inserts a SKU and returns its id. */
    public long sku(String code, int onHand) {
        return jdbc.queryForObject("insert into sku(code, name, on_hand) values (?, ?, ?) returning id",
                Long.class, code, "Item " + code, onHand);
    }

    /** Creates a NEW order through OrderService; SKUs must exist. Returns the order id. */
    public long order(String externalRef, Map<String, Integer> qtyBySkuCode) {
        List<NewOrderCommand.Line> lines = qtyBySkuCode.entrySet().stream()
                .map(e -> new NewOrderCommand.Line(e.getKey(), e.getValue())).toList();
        return orders.create(new NewOrderCommand(externalRef, "Zoë Müller", "Rue de l'Église 12, Sint-Truiden",
                "+32 470 12 34 56", lines)).getId();
    }

    /** Creates (or recreates) a user directly, bypassing method security. */
    public AppUser user(String username, String rawPassword, Role role) {
        users.findByUsername(username).ifPresent(users::delete);
        return users.save(new AppUser(username, encoder.encode(rawPassword), role));
    }
}
