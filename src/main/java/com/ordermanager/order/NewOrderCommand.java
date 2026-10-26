package com.ordermanager.order;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

public record NewOrderCommand(
        @Size(max = 64) String externalRef,
        @NotBlank @Size(max = 120) String shipToName,
        @NotBlank @Size(max = 500) String shipToAddress,
        @Size(max = 40) String shipToPhone,
        @NotEmpty List<@Valid Line> lines) {

    public record Line(@NotBlank String skuCode, @Min(1) @Max(100000) int qty) {
    }
}
