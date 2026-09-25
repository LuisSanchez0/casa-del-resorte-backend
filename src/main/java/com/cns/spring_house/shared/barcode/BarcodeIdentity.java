package com.cns.spring_house.shared.barcode;

import java.math.BigInteger;
import java.util.UUID;

/** Version 1: lossless unsigned UUID, padded for Code 128 subset C digit pairs.
 * The database primary key supplies uniqueness; no truncated UUID or mutable SKU.
 */
public final class BarcodeIdentity {
    private BarcodeIdentity() { }

    public static String product(UUID id) { return encode("P1-", id); }
    public static String item(UUID id) { return encode("I1-", id); }

    private static String encode(String prefix, UUID id) {
        if (id == null) return null; // Never identify an entity not yet persisted.
        String digits = new BigInteger(id.toString().replace("-", ""), 16).toString();
        return prefix + "0".repeat(40 - digits.length()) + digits;
    }
}
