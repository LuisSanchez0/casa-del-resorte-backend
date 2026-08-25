package com.cns.spring_house.workresult.dto;

import com.cns.spring_house.workresult.entity.WorkResultStatus;

public record SearchWorkResultRequestDTO(

    WorkResultStatus status

) {

}
