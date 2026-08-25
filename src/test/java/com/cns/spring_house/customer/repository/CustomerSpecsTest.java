package com.cns.spring_house.customer.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;

import com.cns.spring_house.customer.entity.Customer;

import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;

/**
 * La busqueda por nombre comparaba `lower(name)` contra el termino en
 * MAYUSCULAS, asi que ningun nombre alfabetico coincidia nunca: con el cliente
 * "Cliente Verificacion" guardado, tanto `?name=cliente` como `?name=CLIENTE`
 * devolvian vacio. Aqui se fija que los dos lados van en minusculas.
 */
class CustomerSpecsTest {

    @SuppressWarnings({ "unchecked", "rawtypes" })
    private Predicate nameContainsPredicate(String term, String[] capturedPattern) {
        Root<Customer> root = mock(Root.class);
        Path namePath = mock(Path.class);
        Expression lowered = mock(Expression.class);
        CriteriaBuilder builder = mock(CriteriaBuilder.class);
        Predicate expected = mock(Predicate.class);

        doReturn(namePath).when(root).get("name");
        doReturn(lowered).when(builder).lower(namePath);
        doReturn(expected).when(builder).like(org.mockito.ArgumentMatchers.eq(lowered),
                org.mockito.ArgumentMatchers.anyString());

        Predicate predicate = CustomerSpecs.nameContains(term)
                .toPredicate(root, mock(CriteriaQuery.class), builder);

        org.mockito.ArgumentCaptor<String> captor =
                org.mockito.ArgumentCaptor.forClass(String.class);
        verify(builder).like(org.mockito.ArgumentMatchers.eq(lowered), captor.capture());
        capturedPattern[0] = captor.getValue();

        assertThat(predicate).isEqualTo(expected);
        return predicate;
    }

    @Test
    void comparesBothSidesInLowercase() {
        String[] pattern = new String[1];
        nameContainsPredicate("CLIENTE", pattern);

        // Antes aqui llegaba "%CLIENTE%" contra una columna ya en minusculas.
        assertThat(pattern[0]).isEqualTo("%cliente%");
    }

    @Test
    void aLowercaseTermProducesTheSamePattern() {
        String[] pattern = new String[1];
        nameContainsPredicate("cliente", pattern);

        assertThat(pattern[0]).isEqualTo("%cliente%");
    }

    @Test
    void aMixedCaseTermProducesTheSamePattern() {
        String[] pattern = new String[1];
        nameContainsPredicate("Verificacion", pattern);

        assertThat(pattern[0]).isEqualTo("%verificacion%");
    }

    @Test
    void surroundingSpacesDoNotBecomePartOfTheSearch() {
        String[] pattern = new String[1];
        nameContainsPredicate("  Cliente  ", pattern);

        assertThat(pattern[0]).isEqualTo("%cliente%");
    }

    @Test
    @SuppressWarnings({ "unchecked", "rawtypes" })
    void isIgnoredWhenNoNameIsGiven() {
        Root<Customer> root = mock(Root.class);

        assertThat(CustomerSpecs.nameContains(null)
                .toPredicate(root, mock(CriteriaQuery.class), mock(CriteriaBuilder.class)))
                .isNull();

        assertThat(CustomerSpecs.nameContains("   ")
                .toPredicate(root, mock(CriteriaQuery.class), mock(CriteriaBuilder.class)))
                .isNull();
    }

    /** El NIT no cambia: sigue siendo igualdad exacta, nunca un "contiene". */
    @Test
    @SuppressWarnings({ "unchecked", "rawtypes" })
    void nitRemainsAnExactMatch() {
        Root<Customer> root = mock(Root.class);
        Path nitPath = mock(Path.class);
        CriteriaBuilder builder = mock(CriteriaBuilder.class);
        Predicate expected = mock(Predicate.class);

        doReturn(nitPath).when(root).get("nit");
        doReturn(expected).when(builder).equal(nitPath, "99887766");

        Predicate predicate = CustomerSpecs.hasNit("99887766")
                .toPredicate(root, mock(CriteriaQuery.class), builder);

        assertThat(predicate).isEqualTo(expected);
        verify(builder).equal(nitPath, "99887766");
    }
}
