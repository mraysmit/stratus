package dev.stratus.verification.storage;

public record VerificationCheck(String name, boolean passed, String detail) {
    public static VerificationCheck passed(String name, String detail) {
        return new VerificationCheck(name, true, detail);
    }

    public static VerificationCheck failed(String name, Exception exception) {
        var message = exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
        return new VerificationCheck(name, false, message);
    }
}
