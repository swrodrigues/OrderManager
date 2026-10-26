package com.ordermanager.inventory;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/** A stock-keeping unit. Invariant (also enforced by DB check constraints): 0 <= reserved <= onHand. */
@Entity
@Table(name = "sku")
public class Sku {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String code;

    @Column(nullable = false)
    private String name;

    @Column(name = "on_hand", nullable = false)
    private int onHand;

    @Column(nullable = false)
    private int reserved;

    @Version
    private long version;

    protected Sku() {
    }

    public Sku(String code, String name, int onHand) {
        this.code = code;
        this.name = name;
        this.onHand = onHand;
    }

    public int available() {
        return onHand - reserved;
    }

    void reserve(int qty) { reserved += qty; }
    void release(int qty) { reserved -= qty; }
    void consume(int qty) { onHand -= qty; reserved -= qty; }
    void unconsume(int qty) { onHand += qty; reserved += qty; }
    void adjust(int delta) { onHand += delta; }

    public Long getId() { return id; }
    public String getCode() { return code; }
    public String getName() { return name; }
    public int getOnHand() { return onHand; }
    public int getReserved() { return reserved; }
    public long getVersion() { return version; }

    @Override
    public String toString() {
        return "Sku{" + code + ", onHand=" + onHand + ", reserved=" + reserved + "}";
    }
}
