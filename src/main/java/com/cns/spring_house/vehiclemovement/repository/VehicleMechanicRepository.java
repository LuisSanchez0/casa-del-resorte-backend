package com.cns.spring_house.vehiclemovement.repository;
import java.util.*;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;
import com.cns.spring_house.useraccount.entity.UserAccount;
import com.cns.spring_house.vehiclemovement.dto.MechanicDTO;
public interface VehicleMechanicRepository extends Repository<UserAccount, UUID> {
    @Query("""
      select new com.cns.spring_house.vehiclemovement.dto.MechanicDTO(u.id, concat(e.firstName, ' ', e.lastName))
      from UserAccount u join u.employee e join u.role r
      where u.isActive = true and r.name = 'OPERATOR' and e.workshop.id = :workshop
        and lower(concat(e.firstName, ' ', e.lastName)) like lower(concat('%', :search, '%'))
      order by e.firstName, e.lastName, u.id
      """)
    List<MechanicDTO> search(@Param("workshop") UUID workshop, @Param("search") String search,
        org.springframework.data.domain.Pageable pageable);

    @Query(value = """
      select u.* from user_account u join employee e on e.id=u.employee_id join role r on r.id=u.role_id
      where u.id=:id and u.is_active=true and r.name='OPERATOR' and e.workshop_id=:workshop
      for share of u,e
      """, nativeQuery=true)
    Optional<UserAccount> eligible(@Param("id") UUID id, @Param("workshop") UUID workshop);
}
