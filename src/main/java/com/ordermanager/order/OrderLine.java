package com.ordermanager.order;

import com.ordermanager.inventory.Sku;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "order_line")
public class OrderLine {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id")
    private CustomerOrder order;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "sku_id")
    private Sku sku;

    @Column(nullable = false)
    private int qty;

    protected OrderLine() {
    }

    OrderLine(CustomerOrder order, Sku sku, int qty) {
        this.order = order;
        this.sku = sku;
        this.qty = qty;
    }

    public Long getId() { return id; }
    public CustomerOrder getOrder() { return order; }
    public Sku getSku() { return sku; }
    public int getQty() { return qty; }
}
