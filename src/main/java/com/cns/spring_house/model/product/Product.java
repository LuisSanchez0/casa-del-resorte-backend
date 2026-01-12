package com.cns.spring_house.model.product;

import java.util.Map;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

import com.cns.spring_house.model.audit.Auditable;
import com.cns.spring_house.model.family.Family;
import com.cns.spring_house.model.model.Model;

import jakarta.persistence.Column;
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
public class Product extends Auditable {

    @Id
    @UuidGenerator
    private UUID id;
    private String sku;
    @ManyToOne
    @JoinColumn(name = "family_id")
    private Family family;
    String name;
    @JoinColumn(name = "model_id")
    private Model model;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> attributes;

}
