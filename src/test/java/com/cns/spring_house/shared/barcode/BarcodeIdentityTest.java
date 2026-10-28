package com.cns.spring_house.shared.barcode;

import static org.assertj.core.api.Assertions.assertThat;
import java.math.BigInteger;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class BarcodeIdentityTest {
    @Test void encodesTheEntireUnsignedUuidWithoutTruncation() {
        assertThat(BarcodeIdentity.product(new UUID(0, 1)))
                .isEqualTo("P1-0000000000000000000000000000000000000001");
        assertThat(BarcodeIdentity.item(new UUID(-1, -1)))
                .isEqualTo("I1-0340282366920938463463374607431768211455");
    }
    @Test void neverIdentifiesAnUnpersistedEntity() {
        assertThat(BarcodeIdentity.product(null)).isNull();
        assertThat(BarcodeIdentity.item(null)).isNull();
    }
    @Test void productAndItemNamespacesNeverOverlap() {
        UUID id = UUID.randomUUID();
        assertThat(BarcodeIdentity.product(id)).isNotEqualTo(BarcodeIdentity.item(id));
    }
    @Test void manyParallelIdentitiesAreUniqueAndReversible() {
        var ids = IntStream.range(0, 20000).parallel().mapToObj(i -> UUID.randomUUID()).toList();
        assertThat(ids.stream().map(BarcodeIdentity::product)).doesNotHaveDuplicates();
        assertThat(ids.stream().map(BarcodeIdentity::item)).doesNotHaveDuplicates();
        for (UUID id : ids) {
            BigInteger recovered = new BigInteger(BarcodeIdentity.item(id).substring(3));
            assertThat(new UUID(recovered.shiftRight(64).longValue(), recovered.longValue())).isEqualTo(id);
        }
    }
    @Test void aScannedItemLabelLeadsBackToItsUnit() {
        UUID id = UUID.randomUUID();
        assertThat(BarcodeIdentity.parseItem(BarcodeIdentity.item(id))).contains(id);
        assertThat(BarcodeIdentity.parseItem(" " + BarcodeIdentity.item(id).toLowerCase() + " ")).contains(id);
        assertThat(BarcodeIdentity.parseItem(BarcodeIdentity.item(new UUID(-1, -1)))).contains(new UUID(-1, -1));
    }
    @Test void textThatIsNotAnItemLabelIdentifiesNothing() {
        UUID id = UUID.randomUUID();
        assertThat(BarcodeIdentity.parseItem(BarcodeIdentity.product(id))).isEmpty();
        assertThat(BarcodeIdentity.parseItem("HOJA 105")).isEmpty();
        assertThat(BarcodeIdentity.parseItem("I1-123")).isEmpty();
        assertThat(BarcodeIdentity.parseItem("I1-9999999999999999999999999999999999999999")).isEmpty();
        assertThat(BarcodeIdentity.parseItem(null)).isEmpty();
    }
}
