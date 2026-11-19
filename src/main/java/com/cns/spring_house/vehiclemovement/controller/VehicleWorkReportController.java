package com.cns.spring_house.vehiclemovement.controller;
import java.time.LocalDate;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.access.prepost.PreAuthorize;
import com.cns.spring_house.vehiclemovement.dto.VehicleWorkReportDTO;
import com.cns.spring_house.vehiclemovement.service.VehicleWorkReportService;
@RestController
@RequestMapping("/v1/vehicle-work-report")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class VehicleWorkReportController {
    private final VehicleWorkReportService service;
    @GetMapping
    public VehicleWorkReportDTO report(@RequestParam(required=false) LocalDate from,
        @RequestParam(required=false) LocalDate to, @RequestParam(required=false) UUID mechanicId,
        @RequestParam(defaultValue="ALL") String state, @RequestParam(defaultValue="ENTRY") String dateBasis,
        @RequestParam(defaultValue="0") int page,
        @RequestParam(defaultValue="20") int size, @RequestParam(defaultValue="0") int mechanicPage) {
        return service.report(from,to,mechanicId,state,dateBasis,page,size,mechanicPage);
    }
    @GetMapping("/{id}")
    public VehicleWorkReportDTO.Job detail(@PathVariable UUID id) { return service.detail(id); }
}
