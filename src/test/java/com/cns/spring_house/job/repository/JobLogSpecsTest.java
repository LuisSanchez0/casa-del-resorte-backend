package com.cns.spring_house.job.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.cns.spring_house.job.entity.JobLog;

import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;

class JobLogSpecsTest {

    @Test
    @SuppressWarnings({ "unchecked", "rawtypes" })
    void hasWorkshopNavigatesThroughTheJobRelation() {
        UUID workshopId = UUID.randomUUID();
        Root<JobLog> root = mock(Root.class);
        Path jobPath = mock(Path.class);
        Path workshopPath = mock(Path.class);
        Path idPath = mock(Path.class);
        CriteriaBuilder builder = mock(CriteriaBuilder.class);
        Predicate expected = mock(Predicate.class);

        doReturn(jobPath).when(root).get("job");
        doReturn(workshopPath).when(jobPath).get("workshop");
        doReturn(idPath).when(workshopPath).get("id");
        doReturn(expected).when(builder).equal(idPath, workshopId);

        Predicate predicate = JobLogSpecs.hasWorkshop(workshopId)
                .toPredicate(root, mock(CriteriaQuery.class), builder);

        assertThat(predicate).isEqualTo(expected);
        verify(root).get("job");
        verify(jobPath).get("workshop");
        verify(builder).equal(idPath, workshopId);
    }

    @Test
    @SuppressWarnings({ "unchecked", "rawtypes" })
    void hasWorkshopIsIgnoredWhenNoWorkshopIsGiven() {
        Root<JobLog> root = mock(Root.class);

        Predicate predicate = JobLogSpecs.hasWorkshop(null)
                .toPredicate(root, mock(CriteriaQuery.class),
                        mock(CriteriaBuilder.class));

        assertThat(predicate).isNull();
    }

}
