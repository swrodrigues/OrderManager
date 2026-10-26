package com.ordermanager.order;

/** The last step of an order cannot be undone (nothing to undo, too late, or someone else's change). */
public class UndoNotAllowedException extends RuntimeException {

    public UndoNotAllowedException(String message) {
        super(message);
    }
}
