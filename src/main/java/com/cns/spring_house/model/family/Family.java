package com.cns.spring_house.model.family;

import java.util.List;
import java.util.UUID;

import org.hibernate.annotations.UuidGenerator;

import com.cns.spring_house.model.attribute.Attribute;
import com.cns.spring_house.model.audit.Auditable;

import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
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
    @OneToMany(fetch = FetchType.LAZY, mappedBy = "family")
    List<Attribute> attributes;

}
