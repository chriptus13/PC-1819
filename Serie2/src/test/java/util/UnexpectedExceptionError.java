package util;

public class UnexpectedExceptionError extends RuntimeException {

    public UnexpectedExceptionError(Exception e) {
        super("Unexpected exception", e);
    }
}