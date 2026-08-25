package com.cns.spring_house.shared.security;

import org.springframework.stereotype.Service;

import com.cns.spring_house.employee.entity.Employee;
import com.cns.spring_house.shared.exception.EntityNotFoundException;
import com.cns.spring_house.shared.exception.ValidationException;
import com.cns.spring_house.useraccount.entity.UserAccount;
import com.cns.spring_house.useraccount.repository.UserAccountRepository;
import com.cns.spring_house.workshop.entity.Workshop;

import lombok.RequiredArgsConstructor;

/**
 * Resuelve la identidad autenticada recorriendo la relación real del dominio:
 *
 * <pre>
 * JWT subject = UserAccount.id -&gt; UserAccount -&gt; Employee -&gt; Workshop
 * </pre>
 *
 * Existe porque {@link SecurityUtils#getId()} devuelve el id de la CUENTA, no el
 * del empleado. Buscar un Employee por ese id (`employeeRepository.findById(
 * SecurityUtils.getId())`) nunca encuentra nada, ya que ambos UUID son distintos.
 *
 * Solo resuelve identidad: no contiene lógica de negocio.
 */
@Service
@RequiredArgsConstructor
public class CurrentUserService {

    private final UserAccountRepository userAccountRepository;

    public UserAccount getCurrentUserAccount() {
        return userAccountRepository
                .findById(SecurityUtils.getId())
                .orElseThrow(() -> new EntityNotFoundException(
                        "No existe el usuario autenticado"));
    }

    /**
     * El empleado asociado a la cuenta autenticada. Falla de forma controlada
     * (no con NullPointerException) cuando la cuenta no tiene empleado.
     */
    public Employee getCurrentEmployee() {
        Employee employee = getCurrentUserAccount().getEmployee();

        if (employee == null) {
            throw new ValidationException(
                    "El usuario autenticado no tiene un empleado asociado");
        }

        return employee;
    }

    /** El taller del empleado autenticado, para operaciones que lo exigen. */
    public Workshop getCurrentWorkshop() {
        Workshop workshop = getCurrentEmployee().getWorkshop();

        if (workshop == null) {
            throw new ValidationException(
                    "El empleado autenticado no tiene un taller asociado");
        }

        return workshop;
    }

}
