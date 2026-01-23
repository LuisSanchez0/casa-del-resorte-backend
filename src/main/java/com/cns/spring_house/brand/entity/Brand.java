package com.cns.spring_house.brand.entity;

import java.util.UUID;

import org.hibernate.annotations.UuidGenerator;

import com.cns.spring_house.shared.audit.Auditable;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

@Entity
@Data
@EqualsAndHashCode(of = "id", callSuper = false)
@NoArgsConstructor
public class Brand extends Auditable {
    
    @Id
    @UuidGenerator
    private UUID id;
    private String name;
    private String logoKey;

}
