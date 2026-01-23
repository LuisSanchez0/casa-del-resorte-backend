package com.cns.spring_house.attribute.entity;

import java.util.UUID;

import org.hibernate.annotations.UuidGenerator;

import com.cns.spring_house.family.entity.Family;

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
@EqualsAndHashCode(of = "id")
@NoArgsConstructor
public class Attribute {

    @Id
    @UuidGenerator
    private UUID id;
    @ManyToOne
    @JoinColumn(name = "family_id")
    private Family family;
    private String key;
    @Enumerated(EnumType.STRING)
    private AttributeDataType dataType;
    private Boolean required;

}
