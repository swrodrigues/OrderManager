package com.ordermanager.inventory;

import com.ordermanager.audit.AuditService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * All stock movements. Reserve/release/consume run inside the caller's transaction (MANDATORY) so the
 * stock change commits atomically with the order change that caused it.
 */
@Service
public class StockService {

    private final SkuRepository skus;
    private final AuditService audit;

    public StockService(SkuRepository skus, AuditService audit) {
        this.skus = skus;
        this.audit = audit;
    }

    /** Reserves every line or none: all shortages are reported before anything changes. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void reserve(Map<Long, Integer> qtyBySkuId) {
        List<Sku> locked = skus.lockAllByIdIn(qtyBySkuId.keySet()); // one query, id order
        List<Shortage> shortages = new ArrayList<>();
        for (Sku sku : locked) {
            int qty = qtyBySkuId.get(sku.getId());
            if (sku.available() < qty) {
                shortages.add(new Shortage(sku.getCode(), qty, sku.available()));
            }
        }
        if (locked.size() != qtyBySkuId.size()) {
            throw new IllegalArgumentException("Unknown SKU id in " + qtyBySkuId.keySet());
        }
        if (!shortages.isEmpty()) {
            throw new InsufficientStockException(shortages);
        }
        locked.forEach(sku -> sku.reserve(qtyBySkuId.get(sku.getId())));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void release(Map<Long, Integer> qtyBySkuId) {
        skus.lockAllByIdIn(qtyBySkuId.keySet()).forEach(sku -> sku.release(qtyBySkuId.get(sku.getId())));
    }

    /** Shipping: the goods leave the warehouse, so both on-hand and reserved go down. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void consume(Map<Long, Integer> qtyBySkuId) {
        skus.lockAllByIdIn(qtyBySkuId.keySet()).forEach(sku -> sku.consume(qtyBySkuId.get(sku.getId())));
    }

    /** Undo of a shipment: the goods are back on the shelf and still reserved for the order. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void unconsume(Map<Long, Integer> qtyBySkuId) {
        skus.lockAllByIdIn(qtyBySkuId.keySet()).forEach(sku -> sku.unconsume(qtyBySkuId.get(sku.getId())));
    }

    /** Resolves many codes in one query. Unknown codes are simply absent from the result. */
    @Transactional(readOnly = true)
    public Map<String, Sku> findByCodes(Collection<String> codes) {
        return skus.findByCodeIn(codes).stream().collect(Collectors.toMap(Sku::getCode, Function.identity()));
    }

    @Transactional(readOnly = true)
    public List<Sku> listSkus() {
        return skus.findAllByOrderByCode();
    }

    @Transactional
    @PreAuthorize("hasRole('ADMIN')")
    public Sku createSku(String code, String name, int onHand) {
        if (code == null || !code.matches("[A-Za-z0-9._-]{1,40}")) {
            throw new IllegalArgumentException("SKU code must be 1-40 letters, digits, dots, dashes or underscores");
        }
        if (name == null || name.isBlank() || name.length() > 120) {
            throw new IllegalArgumentException("SKU name is required (max 120 characters)");
        }
        if (onHand < 0) {
            throw new IllegalArgumentException("On hand cannot be negative");
        }
        if (skus.findByCode(code).isPresent()) {
            throw new IllegalArgumentException("SKU code already exists: " + code);
        }
        Sku sku = skus.save(new Sku(code, name.trim(), onHand));
        audit.record("sku", sku.getId(), "CREATE", null, null, Map.of("onHand", onHand));
        return sku;
    }

    @Transactional
    @PreAuthorize("hasRole('ADMIN')")
    public Sku adjustOnHand(long skuId, int delta, String reason) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("A reason is required for stock adjustments");
        }
        List<Sku> locked = skus.lockAllByIdIn(List.of(skuId));
        if (locked.isEmpty()) {
            throw new IllegalArgumentException("SKU not found");
        }
        Sku sku = locked.get(0);
        if (sku.getOnHand() + delta < sku.getReserved()) {
            throw new IllegalArgumentException("On hand cannot go below reserved (" + sku.getReserved() + ")");
        }
        sku.adjust(delta);
        audit.record("sku", sku.getId(), "STOCK_ADJUST", null, null, Map.of("delta", delta, "reason", reason.trim()));
        return sku;
    }
}
