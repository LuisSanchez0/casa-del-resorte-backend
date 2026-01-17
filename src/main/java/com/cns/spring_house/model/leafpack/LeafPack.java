package com.cns.spring_house.model.leafpack;

import java.util.List;
import java.util.UUID;

import org.hibernate.annotations.UuidGenerator;

import com.cns.spring_house.model.audit.Auditable;
import com.cns.spring_house.model.leaf.Leaf;
import com.cns.spring_house.model.product.Product;
import com.cns.spring_house.model.workshop.Workshop;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
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
    @OneToMany(fetch = FetchType.LAZY, mappedBy = "leafPack", cascade = CascadeType.ALL, orphanRemoval = true)
    List<Leaf> leaves;
}
