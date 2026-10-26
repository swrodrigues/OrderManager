package com.ordermanager.importer;

import java.util.List;

/** {@code errors} holds at most {@link CsvImportService#MAX_REPORTED_ERRORS} entries. */
public record ImportResult(long batchId, int ordersImported, int ordersRejected, int rowsOk, int rowsFailed,
                           List<RowError> errors, long durationMs) {
}
