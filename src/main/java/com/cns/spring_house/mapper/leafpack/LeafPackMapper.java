package com.cns.spring_house.mapper.leafpack;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import com.cns.spring_house.config.mapper.MapStructConfig;
import com.cns.spring_house.dto.leafpack.CreateLeafPackRequestDTO;
import com.cns.spring_house.dto.leafpack.LeafPackResponseDTO;
import com.cns.spring_house.mapper.leaf.LeafMapper;
import com.cns.spring_house.model.leafpack.LeafPack;

@Mapper(
    config = MapStructConfig.class,
    uses = { LeafMapper.class }
)
public interface LeafPackMapper {

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "workshop", ignore = true)
    @Mapping(target = "product", ignore = true)
    @Mapping(target = "originalLeafCount", ignore = true)
    @Mapping(target = "availableLeafCount", ignore = true)
    @Mapping(target = "status", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    LeafPack toLeafPack(CreateLeafPackRequestDTO requestDTO);

    LeafPackResponseDTO toResponse(LeafPack leafPack);
}
