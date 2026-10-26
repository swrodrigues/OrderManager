package com.ordermanager.importer;

/** A file with the same SHA-256 was imported before. */
public class DuplicateImportException extends RuntimeException {

    public DuplicateImportException() {
        super("This file was already imported");
    }
}
