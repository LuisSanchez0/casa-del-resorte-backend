package com.cns.spring_house.model.leaf;

import java.math.BigDecimal;
import java.util.UUID;

import org.hibernate.annotations.UuidGenerator;

import com.cns.spring_house.model.audit.Auditable;
import com.cns.spring_house.model.leafpack.LeafPack;

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
public class Leaf extends Auditable{
    @Id
    @UuidGenerator
    UUID id;
    @ManyToOne
    @JoinColumn(name = "leaf_pack_id")
    LeafPack leafPack;
    @Enumerated(EnumType.STRING)
    LeafStatus status;
    String leafNumber;
    BigDecimal width;
    BigDecimal firstLength;
    BigDecimal secondLength;
    Boolean hasHole;
}
