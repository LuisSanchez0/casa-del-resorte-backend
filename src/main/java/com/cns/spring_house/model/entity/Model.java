package com.cns.spring_house.model.entity;

import java.util.UUID;

import org.hibernate.annotations.UuidGenerator;

import com.cns.spring_house.shared.audit.Auditable;
import com.cns.spring_house.brand.entity.Brand;

import jakarta.persistence.Entity;
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
