package com.ordermanager.inventory;

import java.util.List;
import java.util.stream.Collectors;

public class InsufficientStockException extends RuntimeException {

    private final List<Shortage> shortages;

    public InsufficientStockException(List<Shortage> shortages) {
        super("Not enough stock: " + shortages.stream()
                .map(s -> s.skuCode() + " (need " + s.requested() + ", have " + s.available() + ")")
                .collect(Collectors.joining(", ")));
        this.shortages = List.copyOf(shortages);
    }

    public List<Shortage> getShortages() {
        return shortages;
    }
}
