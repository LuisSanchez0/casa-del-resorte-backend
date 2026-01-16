package com.cns.spring_house.model.leafpack;

import java.util.UUID;

import org.hibernate.annotations.UuidGenerator;

import com.cns.spring_house.model.audit.Auditable;
import com.cns.spring_house.model.product.Product;
import com.cns.spring_house.model.workshop.Workshop;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

@Entity
@Data
@EqualsAndHashCode(of = "id", callSuper = false)
@NoArgsConstructor
public class LeafPack extends Auditable {
    @Id
    @UuidGenerator
    UUID id;
    @ManyToOne
    @JoinColumn(name = "product_id")
    Product product;
    @ManyToOne
    @JoinColumn(name = "workshop_id")
    Workshop workshop;
    Integer originalLeafCount;
    Integer availableLeafCount;
    @Enumerated(EnumType.STRING)
    LeafPackStatus status;
}
