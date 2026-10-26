package com.ordermanager.importer;

public record RowError(long lineNo, String externalRef, String message) {
}
