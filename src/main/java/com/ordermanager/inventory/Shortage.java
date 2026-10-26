package com.ordermanager.inventory;

public record Shortage(String skuCode, int requested, int available) {
}
