package com.cns.spring_house.model.useraccount;

import java.util.UUID;

import org.hibernate.annotations.UuidGenerator;

import com.cns.spring_house.model.audit.Auditable;
import com.cns.spring_house.model.employee.Employee;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToOne;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

@Entity
@Data
@EqualsAndHashCode(of = "id", callSuper = false)
@NoArgsConstructor
public class UserAccount extends Auditable {

    @Id
    @UuidGenerator
    private UUID id;
    @ManyToOne
    @JoinColumn(name = "role_id")
    private Role role;
    @OneToOne
    @JoinColumn(name = "employee_id")
    private Employee employee;
    private String username;
    private String password;
    private Boolean isActive;

}
