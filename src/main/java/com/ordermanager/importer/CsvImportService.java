package com.ordermanager.importer;

import com.ordermanager.audit.AuditService;
import com.ordermanager.inventory.Sku;
import com.ordermanager.inventory.StockService;
import com.ordermanager.security.CryptoService;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Bulk order import. The work is O(rows) in Java with a constant number of database round trips per
 * 500 orders:
 * <ol>
 *   <li>stream-parse the CSV while hashing it (one pass over the bytes),</li>
 *   <li>resolve all SKU codes in one query and all existing external refs in one query,</li>
 *   <li>validate in memory; an order with any bad line is rejected as a whole, the rest still import,</li>
 *   <li>insert orders and lines with JDBC batch statements (rewritten by the driver into multi-row INSERTs),
 *       one transaction per 500 orders.</li>
 * </ol>
 * Memory is bounded by the upload limits (5 MB, {@value #MAX_ROWS} rows).
 */
@Service
public class CsvImportService {

    public static final List<String> HEADER =
            List.of("external_ref", "ship_to_name", "ship_to_address", "ship_to_phone", "sku_code", "qty");
    public static final int MAX_ROWS = 20_000;
    public static final int BATCH_SIZE = 500;
    public static final int MAX_REPORTED_ERRORS = 200;

    private final JdbcTemplate jdbc;
    private final StockService stock;
    private final CryptoService crypto;
    private final AuditService audit;
    private final ObjectMapper json;
    private final Clock clock;
    private final TransactionTemplate tx;

    public CsvImportService(JdbcTemplate jdbc, StockService stock, CryptoService crypto, AuditService audit,
                            ObjectMapper json, Clock clock, PlatformTransactionManager txManager) {
        this.jdbc = jdbc;
        this.stock = stock;
        this.crypto = crypto;
        this.audit = audit;
        this.json = json;
        this.clock = clock;
        this.tx = new TransactionTemplate(txManager);
    }

    public ImportResult importCsv(String filename, InputStream in, String username) {
        long start = System.nanoTime();
        MessageDigest sha256 = sha256();
        Parsed parsed = parse(new DigestInputStream(in, sha256));
        String hash = HexFormat.of().formatHex(sha256.digest());

        long batchId = claimBatch(filename, hash, username);
        try {
            return process(batchId, parsed, username, start);
        } catch (RuntimeException e) {
            // let the same file be retried; orders already committed are protected by unique external_ref
            jdbc.update("delete from import_batch where id = ?", batchId);
            throw e;
        }
    }

    // ---- 1. parse ----

    /** {@code poisonedRefs}: orders with an unparseable row; their other rows must not import on their own. */
    private record Parsed(Map<String, List<CsvRow>> byRef, List<RowError> rowErrors, Set<String> poisonedRefs) {
    }

    private Parsed parse(InputStream in) {
        // strict decoder: a non-UTF-8 file (e.g. Windows-1252) is rejected instead of silently garbled
        Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT));
        Map<String, List<CsvRow>> byRef = new LinkedHashMap<>();
        List<RowError> rowErrors = new ArrayList<>();
        Set<String> poisoned = new HashSet<>();
        int rows = 0;
        try (CSVParser parser = CSVFormat.DEFAULT.builder().setIgnoreEmptyLines(true).build().parse(reader)) {
            boolean header = true;
            for (CSVRecord rec : parser) {
                if (header) {
                    checkHeader(rec);
                    header = false;
                    continue;
                }
                if (++rows > MAX_ROWS) {
                    throw new ImportRejectedException("File has more than " + MAX_ROWS + " rows (max " + MAX_ROWS + " rows)");
                }
                long lineNo = rec.getRecordNumber();
                if (rec.size() != HEADER.size()) {
                    String ref = rec.get(0).trim();
                    rowErrors.add(new RowError(lineNo, ref,
                            "expected " + HEADER.size() + " columns, found " + rec.size()
                                    + " (put values that contain commas in double quotes)"));
                    poisoned.add(ref);
                    continue;
                }
                CsvRow row = new CsvRow(lineNo, rec.get(0), rec.get(1), rec.get(2), rec.get(3), rec.get(4), rec.get(5));
                byRef.computeIfAbsent(row.ref(), k -> new ArrayList<>()).add(row);
            }
            if (header) {
                throw new ImportRejectedException("File is empty");
            }
            in.transferTo(java.io.OutputStream.nullOutputStream()); // finish hashing any trailing bytes
        } catch (IOException e) {
            throw unreadable(e);
        } catch (UncheckedIOException e) { // commons-csv wraps lexer errors (bad quoting) like this
            throw unreadable(e.getCause());
        } catch (IllegalStateException | IllegalArgumentException e) {
            throw new ImportRejectedException("The file is not valid CSV: " + e.getMessage());
        }
        if (rows == 0) {
            throw new ImportRejectedException("File has no data rows");
        }
        return new Parsed(byRef, rowErrors, poisoned);
    }

    private static ImportRejectedException unreadable(IOException e) {
        if (e instanceof CharacterCodingException) {
            return new ImportRejectedException(
                    "File is not UTF-8. In Excel use Save as → \"CSV UTF-8 (comma delimited)\".");
        }
        return new ImportRejectedException("The file is not valid CSV: " + e.getMessage());
    }

    private static void checkHeader(CSVRecord rec) {
        List<String> actual = new ArrayList<>();
        for (int i = 0; i < rec.size(); i++) {
            String v = rec.get(i).trim().toLowerCase();
            actual.add(i == 0 ? v.replace("﻿", "") : v); // Excel's UTF-8 byte order mark
        }
        if (!actual.equals(HEADER)) {
            throw new ImportRejectedException("expected comma-separated header: " + String.join(",", HEADER));
        }
    }

    // ---- 2. claim the file (duplicate check) ----

    private long claimBatch(String filename, String hash, String username) {
        try {
            return jdbc.queryForObject("insert into import_batch(filename, file_sha256, status, created_by) "
                    + "values (?, ?, 'PROCESSING', ?) returning id", Long.class, truncate(filename, 255), hash, username);
        } catch (DuplicateKeyException e) {
            throw new DuplicateImportException();
        }
    }

    // ---- 3. validate, 4. insert ----

    private ImportResult process(long batchId, Parsed parsed, String username, long start) {
        Set<String> codes = parsed.byRef().values().stream().flatMap(List::stream).map(CsvRow::sku)
                .collect(Collectors.toSet());
        Map<String, Sku> skus = stock.findByCodes(codes);                                  // one query
        Set<String> existingRefs = new HashSet<>(jdbc.queryForList(                         // one query
                "select external_ref from customer_order where external_ref = any(?)", String.class,
                (Object) parsed.byRef().keySet().toArray(String[]::new)));

        List<RowError> errors = new ArrayList<>(parsed.rowErrors());
        int rowsFailed = parsed.rowErrors().size();
        // orders whose only rows were unparseable are rejected too
        int ordersRejected = (int) parsed.poisonedRefs().stream().filter(r -> !parsed.byRef().containsKey(r)).count();
        List<List<CsvRow>> valid = new ArrayList<>();

        for (Map.Entry<String, List<CsvRow>> e : parsed.byRef().entrySet()) {
            List<RowError> orderErrors = parsed.poisonedRefs().contains(e.getKey())
                    ? List.of(new RowError(e.getValue().get(0).lineNo(), e.getKey(),
                            "order rejected: another line of this order could not be read"))
                    : validateOrder(e.getKey(), e.getValue(), skus.keySet(), existingRefs);
            if (orderErrors.isEmpty()) {
                valid.add(e.getValue());
            } else {
                errors.addAll(orderErrors);
                rowsFailed += e.getValue().size();
                ordersRejected++;
            }
        }
        errors.sort((a, b) -> Long.compare(a.lineNo(), b.lineNo()));

        int rowsOk = 0;
        for (int i = 0; i < valid.size(); i += BATCH_SIZE) {
            List<List<CsvRow>> chunk = valid.subList(i, Math.min(i + BATCH_SIZE, valid.size()));
            tx.executeWithoutResult(s -> insertChunk(chunk, skus, username));
            rowsOk += chunk.stream().mapToInt(List::size).sum();
        }

        List<RowError> reported = errors.size() > MAX_REPORTED_ERRORS ? List.copyOf(errors.subList(0, MAX_REPORTED_ERRORS)) : List.copyOf(errors);
        long durationMs = (System.nanoTime() - start) / 1_000_000;
        int ok = rowsOk;
        int failed = rowsFailed;
        int rejected = ordersRejected;
        tx.executeWithoutResult(s -> {
            jdbc.update("update import_batch set rows_ok = ?, rows_failed = ?, status = 'COMPLETED', errors = ?::jsonb, "
                    + "duration_ms = ? where id = ?", ok, failed, toJson(reported), durationMs, batchId);
            audit.record("import_batch", batchId, "IMPORT", null, null,
                    Map.of("ordersImported", valid.size(), "ordersRejected", rejected, "rowsOk", ok, "rowsFailed", failed));
        });
        return new ImportResult(batchId, valid.size(), ordersRejected, rowsOk, rowsFailed, reported, durationMs);
    }

    private static List<RowError> validateOrder(String ref, List<CsvRow> rows, Set<String> knownSkus,
                                                Set<String> existingRefs) {
        List<RowError> errors = new ArrayList<>();
        CsvRow first = rows.get(0);
        Set<String> seenSkus = new HashSet<>();
        for (CsvRow row : rows) {
            List<String> problems = new ArrayList<>(CsvRowValidator.validate(row, knownSkus));
            if (row != first && problems.isEmpty() && !row.sameShipTo(first)) {
                problems.add("ship-to details differ from line " + first.lineNo() + " of the same order");
            }
            if (!row.sku().isEmpty() && !seenSkus.add(row.sku())) {
                problems.add("SKU " + row.sku() + " appears twice in the same order");
            }
            if (!problems.isEmpty()) {
                errors.add(new RowError(row.lineNo(), ref, String.join("; ", problems)));
            }
        }
        if (errors.isEmpty() && existingRefs.contains(ref)) {
            errors.add(new RowError(first.lineNo(), ref, "external_ref " + ref + " already exists"));
        }
        return errors;
    }

    private void insertChunk(List<List<CsvRow>> orders, Map<String, Sku> skus, String username) {
        // reserve ids up front so order lines can reference them without a round trip per order
        List<Long> ids = jdbc.queryForList(
                "select nextval(pg_get_serial_sequence('customer_order', 'id')) from generate_series(1, ?)",
                Long.class, orders.size());
        Timestamp now = Timestamp.from(clock.instant().truncatedTo(ChronoUnit.MICROS));

        List<Object[]> orderArgs = new ArrayList<>(orders.size());
        List<Object[]> lineArgs = new ArrayList<>();
        for (int i = 0; i < orders.size(); i++) {
            CsvRow first = orders.get(i).get(0);
            long id = ids.get(i);
            orderArgs.add(new Object[]{id, first.ref(), first.name(), crypto.encrypt(first.address()),
                    crypto.encrypt(first.phone()), now, username});
            for (CsvRow row : orders.get(i)) {
                lineArgs.add(new Object[]{id, skus.get(row.sku()).getId(), row.qty()});
            }
        }
        jdbc.batchUpdate("insert into customer_order(id, external_ref, status, ship_to_name, ship_to_address_enc, "
                + "ship_to_phone_enc, created_at, created_by) values (?, ?, 'NEW', ?, ?, ?, ?, ?)", orderArgs);
        jdbc.batchUpdate("insert into order_line(order_id, sku_id, qty) values (?, ?, ?)", lineArgs);
    }

    private String toJson(List<RowError> errors) {
        return json.writeValueAsString(errors); // Jackson 3: unchecked JacksonException
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String truncate(String s, int max) {
        String v = s == null || s.isBlank() ? "upload.csv" : s;
        return v.length() <= max ? v : v.substring(0, max);
    }
}
