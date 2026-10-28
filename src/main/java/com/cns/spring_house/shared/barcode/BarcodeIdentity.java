package com.cns.spring_house.shared.barcode;

import java.math.BigInteger;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/** Version 1: lossless unsigned UUID, padded for Code 128 subset C digit pairs.
 * The database primary key supplies uniqueness; no truncated UUID or mutable SKU.
 */
public final class BarcodeIdentity {
    private BarcodeIdentity() { }

    public static String product(UUID id) { return encode("P1-", id); }
    public static String item(UUID id) { return encode("I1-", id); }

    private static final Pattern ITEM_CODE = Pattern.compile("(?i)I1-(\\d{40})");

    /**
     * The unit a scanned item label identifies, or empty if the text is not an
     * item label. Exact inverse of {@link #item(UUID)}.
     */
    public static Optional<UUID> parseItem(String value) {
        if (value == null) return Optional.empty();
        var matcher = ITEM_CODE.matcher(value.trim());
        if (!matcher.matches()) return Optional.empty();
        BigInteger number = new BigInteger(matcher.group(1));
        if (number.bitLength() > 128) return Optional.empty();
        return Optional.of(new UUID(number.shiftRight(64).longValue(), number.longValue()));
    }

    private static String encode(String prefix, UUID id) {
        if (id == null) return null; // Never identify an entity not yet persisted.
        String digits = new BigInteger(id.toString().replace("-", ""), 16).toString();
        return prefix + "0".repeat(40 - digits.length()) + digits;
    }
}
