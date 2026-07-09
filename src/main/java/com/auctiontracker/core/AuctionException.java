package com.auctiontracker.core;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Domain error with a machine-readable code and an admin-relayable message
 * (DESIGN.md 5.3: failures must carry a specific, actionable reason).
 * Kind maps to an HTTP status in the web layer; core stays framework-free.
 */
public class AuctionException extends RuntimeException {

    public enum Kind { NOT_FOUND, CONFLICT, BAD_REQUEST }

    private final Kind kind;
    private final String code;
    private final Map<String, Object> details;

    private AuctionException(Kind kind, String code, String message, Map<String, Object> details) {
        super(message);
        this.kind = kind;
        this.code = code;
        this.details = details == null ? Map.of() : details;
    }

    public static AuctionException notFound(String code, String message) {
        return new AuctionException(Kind.NOT_FOUND, code, message, null);
    }

    public static AuctionException conflict(String code, String message) {
        return new AuctionException(Kind.CONFLICT, code, message, null);
    }

    public static AuctionException conflict(String code, String message, Map<String, Object> details) {
        return new AuctionException(Kind.CONFLICT, code, message, new LinkedHashMap<>(details));
    }

    public static AuctionException badRequest(String code, String message) {
        return new AuctionException(Kind.BAD_REQUEST, code, message, null);
    }

    public Kind getKind() { return kind; }
    public String getCode() { return code; }
    public Map<String, Object> getDetails() { return details; }
}
