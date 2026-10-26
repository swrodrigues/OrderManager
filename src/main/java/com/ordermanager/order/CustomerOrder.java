package com.ordermanager.order;

import com.ordermanager.inventory.Sku;
import com.ordermanager.security.EncryptedStringConverter;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Named CustomerOrder because ORDER is a reserved word in SQL and JPQL. */
@Entity
@Table(name = "customer_order")
public class CustomerOrder {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Filled by the database default (ORD- + sequence). */
    @Generated(event = EventType.INSERT)
    @Column(name = "order_no", insertable = false, updatable = false)
    private String orderNo;

    @Column(name = "external_ref")
    private String externalRef;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private OrderStatus status;

    @Column(name = "ship_to_name", nullable = false)
    private String shipToName;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "ship_to_address_enc", nullable = false)
    private String shipToAddress;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "ship_to_phone_enc")
    private String shipToPhone;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "shipped_at")
    private Instant shippedAt;

    @Column(name = "delivered_at")
    private Instant deliveredAt;

    @Column(name = "created_by", nullable = false)
    private String createdBy;

    @Version
    private long version;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<OrderLine> lines = new ArrayList<>();

    protected CustomerOrder() {
    }

    public CustomerOrder(String externalRef, String shipToName, String shipToAddress, String shipToPhone,
                         Instant createdAt, String createdBy) {
        this.externalRef = externalRef;
        this.shipToName = shipToName;
        this.shipToAddress = shipToAddress;
        this.shipToPhone = shipToPhone;
        this.createdAt = createdAt;
        this.createdBy = createdBy;
        this.status = OrderStatus.NEW;
    }

    public void addLine(Sku sku, int qty) {
        lines.add(new OrderLine(this, sku, qty));
    }

    public Map<Long, Integer> qtyBySkuId() {
        Map<Long, Integer> m = new LinkedHashMap<>();
        for (OrderLine l : lines) {
            m.merge(l.getSku().getId(), l.getQty(), Integer::sum);
        }
        return m;
    }

    void setStatus(OrderStatus status) { this.status = status; }
    void setShippedAt(Instant shippedAt) { this.shippedAt = shippedAt; }
    void setDeliveredAt(Instant deliveredAt) { this.deliveredAt = deliveredAt; }

    public Long getId() { return id; }
    public String getOrderNo() { return orderNo; }
    public String getExternalRef() { return externalRef; }
    public OrderStatus getStatus() { return status; }
    public String getShipToName() { return shipToName; }
    public String getShipToAddress() { return shipToAddress; }
    public String getShipToPhone() { return shipToPhone; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getShippedAt() { return shippedAt; }
    public Instant getDeliveredAt() { return deliveredAt; }
    public String getCreatedBy() { return createdBy; }
    public long getVersion() { return version; }
    public Instant getDeletedAt() { return deletedAt; }
    public List<OrderLine> getLines() { return lines; }

    /** Never includes ship-to address or phone (personal data). */
    @Override
    public String toString() {
        return "CustomerOrder{id=" + id + ", orderNo=" + orderNo + ", status=" + status + "}";
    }
}
