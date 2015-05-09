package net.sf.fdshare;

public final class ConnectionBrokenException extends Exception {
    ConnectionBrokenException(String reason) {
        super(reason);
    }
}
