package com.cns.spring_house.family.entity;

import java.util.List;
import java.util.UUID;

import org.hibernate.annotations.UuidGenerator;

import com.cns.spring_house.attribute.entity.Attribute;
import com.cns.spring_house.shared.audit.Auditable;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

@Entity
@Data
@EqualsAndHashCode(of = "id", callSuper = false)
@NoArgsConstructor
public class Family extends Auditable {

    @Id
    @UuidGenerator
    private UUID id;
    private String code;
    private String name;
    private Boolean requiresModel;
    @OneToMany(mappedBy = "family")
    List<Attribute> attributes;

}
