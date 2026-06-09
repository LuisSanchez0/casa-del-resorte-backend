package com.cns.spring_house.customer.service;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.cns.spring_house.customer.dto.CreateCustomerRequestDTO;
import com.cns.spring_house.customer.dto.CustomerResponseDTO;
import com.cns.spring_house.customer.dto.SearchCustomerRequestDTO;
import com.cns.spring_house.customer.dto.UpdateCustomerRequestDTO;
import com.cns.spring_house.customer.entity.Customer;
import com.cns.spring_house.customer.mapper.CustomerMapper;
import com.cns.spring_house.customer.repository.CustomerRepository;
import com.cns.spring_house.customer.repository.CustomerSpecs;
import com.cns.spring_house.shared.exception.EntityAlreadyExistsException;
import com.cns.spring_house.shared.exception.EntityNotFoundException;

import lombok.RequiredArgsConstructor;

@Service
@Transactional
@RequiredArgsConstructor
public class CustomerServiceImpl implements CustomerService {

    private final CustomerRepository repository;
    private final CustomerMapper mapper;
    
    @Override
    public List<CustomerResponseDTO> getAll(SearchCustomerRequestDTO requestDTO) {
        Specification<Customer> specs = Specification.allOf(
                CustomerSpecs.nameContains(requestDTO.name()),
                CustomerSpecs.hasNit(requestDTO.nit()));
        return repository.findAll(specs).stream()
                .map(mapper::toResponse)
                .toList();
    }

    @Override
    public CustomerResponseDTO get(UUID id) {
        Customer customer = repository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException(
                        "No existe el cliente solicitado"));
        return mapper.toResponse(customer);
    }

    @Override
    public CustomerResponseDTO create(CreateCustomerRequestDTO requestDTO) {
        boolean alreadyExists = repository.existsByNit(requestDTO.nit());
        if (alreadyExists) {
            throw new EntityAlreadyExistsException(
                    "Ya existe un cliente registrado con ese nit");
        }
        Customer customer = mapper.toCustomer(requestDTO);
        customer = repository.save(customer);
        return mapper.toResponse(customer);
    }

    @Override
    public CustomerResponseDTO update(UUID id, UpdateCustomerRequestDTO requestDTO) {
        Customer customer = repository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException(
                        "No existe el cliente solicitado"));
        boolean alreadyExists = repository.existsByNitAndIdNot(requestDTO.nit(), id);
        if (alreadyExists) {
            throw new EntityAlreadyExistsException(
                    "Ya existe un cliente registrado con ese nit");
        }
        mapper.updateCustomer(requestDTO, customer);
        customer = repository.save(customer);
        return mapper.toResponse(customer);
    }
}
