package com.cns.spring_house.workresult.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.cns.spring_house.inventory.entity.Inventory;
import com.cns.spring_house.inventory.entity.InventoryItem;
import com.cns.spring_house.job.entity.Job;
import com.cns.spring_house.job.entity.JobLog;
import com.cns.spring_house.product.entity.Product;
import com.cns.spring_house.workresult.dto.WorkResultResponseDTO;
import com.cns.spring_house.workresult.dto.WorkResultSource;
import com.cns.spring_house.workresult.entity.WorkResult;
import com.cns.spring_house.workresult.entity.WorkResultStatus;
import com.cns.spring_house.workshop.entity.Workshop;

class WorkResultMapperTest {

    private final WorkResultMapper mapper = new WorkResultMapperImpl();

    private final Workshop workshop = Workshop.builder()
            .id(UUID.randomUUID())
            .build();

    @Test
    void jobLogRecordIsReportedAsAJobWithItsPerformedDate() {
        Product product = Product.builder()
                .id(UUID.randomUUID())
                .name("CAMBIO DE RESORTE")
                .build();
        LocalDateTime performedDate = LocalDateTime.of(2026, 8, 4, 10, 30);
        JobLog jobLog = JobLog.builder()
                .id(UUID.randomUUID())
                .performedDate(performedDate)
                .job(Job.builder()
                        .id(UUID.randomUUID())
                        .workshop(workshop)
                        .product(product)
                        .build())
                .build();
        WorkResult workResult = WorkResult.builder()
                .id(UUID.randomUUID())
                .workshop(workshop)
                .jobLog(jobLog)
                .build();
        workResult.setCreatedAt(LocalDateTime.of(2026, 8, 4, 23, 0));

        WorkResultResponseDTO response = mapper.toResponse(workResult);

        assertThat(response.source()).isEqualTo(WorkResultSource.JOB);
        assertThat(response.status()).isEqualTo(WorkResultStatus.PENDING);
        assertThat(response.jobLogId()).isEqualTo(jobLog.getId());
        assertThat(response.itemId()).isNull();
        assertThat(response.productId()).isEqualTo(product.getId());
        assertThat(response.productName()).isEqualTo("CAMBIO DE RESORTE");
        assertThat(response.performedDate()).isEqualTo(performedDate);
    }

    @Test
    void jobRecordFallsBackToTheWorkResultDateWhenThereIsNoPerformedDate() {
        JobLog jobLog = JobLog.builder()
                .id(UUID.randomUUID())
                .job(Job.builder()
                        .id(UUID.randomUUID())
                        .workshop(workshop)
                        .product(Product.builder()
                                .id(UUID.randomUUID())
                                .name("SERVICIO")
                                .build())
                        .build())
                .build();
        LocalDateTime createdAt = LocalDateTime.of(2026, 8, 4, 21, 45);
        WorkResult workResult = WorkResult.builder()
                .id(UUID.randomUUID())
                .workshop(workshop)
                .jobLog(jobLog)
                .build();
        workResult.setCreatedAt(createdAt);

        WorkResultResponseDTO response = mapper.toResponse(workResult);

        assertThat(response.performedDate()).isEqualTo(createdAt);
    }

    @Test
    void itemRecordFallsBackToTheWorkResultDateWhenTheItemHasNoAuditDate() {
        InventoryItem item = InventoryItem.builder()
                .id(UUID.randomUUID())
                .inventory(Inventory.builder()
                        .workshop(workshop)
                        .product(Product.builder()
                                .id(UUID.randomUUID())
                                .name("TRANSFORMADO")
                                .build())
                        .build())
                .build();
        LocalDateTime createdAt = LocalDateTime.of(2026, 8, 4, 19, 30);
        WorkResult workResult = WorkResult.builder()
                .id(UUID.randomUUID())
                .workshop(workshop)
                .item(item)
                .build();
        workResult.setCreatedAt(createdAt);

        WorkResultResponseDTO response = mapper.toResponse(workResult);

        assertThat(response.performedDate()).isEqualTo(createdAt);
    }

    @Test
    void itemRecordIsReportedAsATransformationWithItsCreationDate() {
        Product product = Product.builder()
                .id(UUID.randomUUID())
                .name("RESORTE CORTADO")
                .build();
        InventoryItem item = InventoryItem.builder()
                .id(UUID.randomUUID())
                .inventory(Inventory.builder()
                        .workshop(workshop)
                        .product(product)
                        .build())
                .build();
        LocalDateTime itemCreatedAt = LocalDateTime.of(2026, 8, 4, 18, 15);
        item.setCreatedAt(itemCreatedAt);
        WorkResult workResult = WorkResult.builder()
                .id(UUID.randomUUID())
                .workshop(workshop)
                .item(item)
                .build();
        workResult.setCreatedAt(LocalDateTime.of(2026, 8, 4, 23, 59));

        WorkResultResponseDTO response = mapper.toResponse(workResult);

        assertThat(response.source())
                .isEqualTo(WorkResultSource.TRANSFORMATION);
        assertThat(response.itemId()).isEqualTo(item.getId());
        assertThat(response.jobLogId()).isNull();
        assertThat(response.productId()).isEqualTo(product.getId());
        assertThat(response.productName()).isEqualTo("RESORTE CORTADO");
        assertThat(response.performedDate()).isEqualTo(itemCreatedAt);
    }

}
