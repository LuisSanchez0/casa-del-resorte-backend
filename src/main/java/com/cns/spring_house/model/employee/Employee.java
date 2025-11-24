package com.cns.spring_house.model.employee;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import org.hibernate.annotations.UuidGenerator;

import com.cns.spring_house.model.audit.Auditable;
import com.cns.spring_house.model.workshop.Workshop;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

@Entity
@Data
@EqualsAndHashCode(of = "id", callSuper = false)
@NoArgsConstructor
public class Employee extends Auditable {

    @Id
    @UuidGenerator
    private UUID id;
    @ManyToOne
    @JoinColumn(name = "workshop_id")
    private Workshop workshop;
    private String firstName;
    private String lastName;
    private String dpi;
    private LocalDate birthDate;
    @OneToMany(mappedBy = "employee", cascade = CascadeType.ALL, orphanRemoval = true)
    private Set<EmployeePhoneNumber> phoneNumbers = new HashSet<>();

}
