package com.cns.spring_house.dailylog.entity;

import java.time.LocalDate;
import java.util.UUID;

import org.hibernate.annotations.UuidGenerator;

import com.cns.spring_house.employee.entity.Employee;
import com.cns.spring_house.shared.audit.Auditable;
import com.cns.spring_house.workshop.entity.Workshop;

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
public class DailyLog extends Auditable {

    @UuidGenerator
    @Id
    private UUID id;
    private LocalDate performedDate;
    @ManyToOne
    @JoinColumn(name = "workshop_id")
    private Workshop workshop;
    @ManyToOne
    @JoinColumn(name = "employee_id")
    private Employee employee;
    private String description;
    
}
