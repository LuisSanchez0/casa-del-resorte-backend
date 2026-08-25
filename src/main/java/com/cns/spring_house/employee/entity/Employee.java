package com.cns.spring_house.employee.entity;

import java.time.LocalDate;
import java.util.UUID;

import org.hibernate.annotations.UuidGenerator;

import com.cns.spring_house.shared.audit.Auditable;
import com.cns.spring_house.workshop.entity.Workshop;

import com.cns.spring_house.shared.constants.ColumnLimits;

import jakarta.persistence.Column;
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
public class Employee extends Auditable {

    @Id
    @UuidGenerator
    private UUID id;
    @ManyToOne
    @JoinColumn(name = "workshop_id")
    private Workshop workshop;
    @Column(length = ColumnLimits.EMPLOYEE_NAME)
    private String firstName;
    @Column(length = ColumnLimits.EMPLOYEE_NAME)
    private String lastName;
    @Column(length = ColumnLimits.EMPLOYEE_DPI)
    private String dpi;
    @Column(length = ColumnLimits.EMPLOYEE_PHONE)
    private String phoneNumber;
    private LocalDate birthDate;

}
