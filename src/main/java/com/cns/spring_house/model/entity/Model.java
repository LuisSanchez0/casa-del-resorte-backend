package com.cns.spring_house.model.entity;

import java.util.UUID;

import org.hibernate.annotations.UuidGenerator;

import com.cns.spring_house.shared.audit.Auditable;
import com.cns.spring_house.brand.entity.Brand;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

@Entity
@Getter
@Setter
@SuperBuilder
@EqualsAndHashCode(of = "id", callSuper = false)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class Model extends Auditable {

    @Id
    @UuidGenerator
    private UUID id;
    @ManyToOne
    @JoinColumn(name = "brand_id")
    private Brand brand;
    private String name;
    private Integer year;

}
