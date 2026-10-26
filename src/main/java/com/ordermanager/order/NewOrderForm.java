package com.ordermanager.order;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.ArrayList;
import java.util.List;

/** Mutable form-backing object for the New order page (Spring binds lines[0].skuCode etc.). */
public class NewOrderForm {

    @Size(max = 64)
    private String externalRef;

    @NotBlank
    @Size(max = 120)
    private String shipToName;

    @NotBlank
    @Size(max = 500)
    private String shipToAddress;

    @Size(max = 40)
    private String shipToPhone;

    @NotEmpty(message = "add at least one line")
    @Valid
    private List<LineForm> lines = new ArrayList<>(List.of(new LineForm()));

    /** Rows the user left completely empty are ignored rather than reported as errors. */
    public void dropEmptyLines() {
        lines.removeIf(l -> (l.getSkuCode() == null || l.getSkuCode().isBlank()) && l.getQty() == null);
    }

    public NewOrderCommand toCommand() {
        return new NewOrderCommand(externalRef, shipToName, shipToAddress, shipToPhone,
                lines.stream().map(l -> new NewOrderCommand.Line(l.getSkuCode().trim(), l.getQty())).toList());
    }

    public static class LineForm {
        @NotBlank
        private String skuCode;

        @NotNull
        @Min(1)
        @Max(100000)
        private Integer qty;

        public String getSkuCode() { return skuCode; }
        public void setSkuCode(String skuCode) { this.skuCode = skuCode; }
        public Integer getQty() { return qty; }
        public void setQty(Integer qty) { this.qty = qty; }
    }

    public String getExternalRef() { return externalRef; }
    public void setExternalRef(String externalRef) { this.externalRef = externalRef; }
    public String getShipToName() { return shipToName; }
    public void setShipToName(String shipToName) { this.shipToName = shipToName; }
    public String getShipToAddress() { return shipToAddress; }
    public void setShipToAddress(String shipToAddress) { this.shipToAddress = shipToAddress; }
    public String getShipToPhone() { return shipToPhone; }
    public void setShipToPhone(String shipToPhone) { this.shipToPhone = shipToPhone; }
    public List<LineForm> getLines() { return lines; }
    public void setLines(List<LineForm> lines) { this.lines = lines; }
}
