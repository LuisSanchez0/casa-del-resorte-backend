package com.cns.spring_house.customer.entity;

import java.util.UUID;

import org.hibernate.annotations.UuidGenerator;

import com.cns.spring_house.shared.audit.Auditable;

import com.cns.spring_house.shared.constants.ColumnLimits;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
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
public class Customer extends Auditable {
    
    @UuidGenerator
    @Id
    private UUID id;
    @Column(length = ColumnLimits.CUSTOMER_NAME)
    private String name;
    @Column(length = ColumnLimits.CUSTOMER_NIT)
    private String nit;

}
