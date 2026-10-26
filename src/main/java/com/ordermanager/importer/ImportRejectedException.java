package com.ordermanager.importer;

/** The whole file was refused (bad header, empty, too many rows). Nothing was imported. */
public class ImportRejectedException extends RuntimeException {

    public ImportRejectedException(String message) {
        super(message);
    }
}
