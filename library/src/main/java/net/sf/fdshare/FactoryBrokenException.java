package net.sf.fdshare;

/**
 * This exception is thrown by factory to indicate, that it is not usable anymore.
 * By the time it was thrown the factory is already closed (but there is no harm from
 * closing it again). Attempting to reuse already closed factory will result in
 * another instance of this exception being thrown.
 */
public final class FactoryBrokenException extends Exception {
    FactoryBrokenException(String reason) {
        super(reason);
    }
}
