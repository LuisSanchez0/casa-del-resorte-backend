package com.cns.spring_house.repository.employee;

import java.util.UUID;

import org.springframework.data.jpa.domain.Specification;

import com.cns.spring_house.model.employee.Employee;

public class EmployeeSpecs {

    private EmployeeSpecs() {

    }

    public static Specification<Employee> firstNameContains(String firstName) {
        return (root, query, criteriaBuilder) -> firstName == null ? null
                : criteriaBuilder.like(
                        criteriaBuilder.lower(root.get("firstName")),
                        "%" + firstName.toLowerCase() + "%");
    }

    public static Specification<Employee> lastNameContains(String lastName) {
        return (root, query, criteriaBuilder) -> lastName == null ? null
                : criteriaBuilder.like(
                        criteriaBuilder.lower(root.get("lastName")),
                        "%" + lastName.toLowerCase() + "%");
    }

    public static Specification<Employee> dpiContains(String dpi) {
        return (root, query, criteriaBuilder) -> dpi == null ? null
                : criteriaBuilder.like(
                        root.get("dpi"),
                        "%" + dpi + "%");
    }

    public static Specification<Employee> phoneNumberContains(String phoneNumber) {
        return (root, query, criteriaBuilder) -> phoneNumber == null ? null
                : criteriaBuilder.like(
                        root.get("phoneNumber"),
                        "%" + phoneNumber + "%");
    }

    public static Specification<Employee> hasWorkshop(UUID workshop) {
        return (root, query, criteriaBuilder) -> workshop == null ? null
                : criteriaBuilder.equal(
                        root.get("workshop").get("id"),
                        workshop);
    }

}
