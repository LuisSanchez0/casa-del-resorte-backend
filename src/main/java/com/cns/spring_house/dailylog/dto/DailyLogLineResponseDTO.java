package com.cns.spring_house.dailylog.dto;

import java.math.BigDecimal;

/**
 * Linea de un Daily Log tal y como se devuelve: solo texto e importes.
 *
 * La misma forma sirve para productos y para servicios porque ambos guardan lo
 * mismo. No expone —ni puede exponer— entidades {@code Product} o {@code Job}:
 * el registro no las tiene.
 */
public record DailyLogLineResponseDTO(

        String name,
        BigDecimal unitPrice,
        Integer quantity,
        BigDecimal subtotal

) {
}
