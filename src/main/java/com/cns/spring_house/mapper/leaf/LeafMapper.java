package com.cns.spring_house.mapper.leaf;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import com.cns.spring_house.config.mapper.MapStructConfig;
import com.cns.spring_house.dto.leaf.CreateLeafRequestDTO;
import com.cns.spring_house.dto.leaf.LeafResponseDTO;
import com.cns.spring_house.model.leaf.Leaf;

@Mapper(config = MapStructConfig.class)
public interface LeafMapper {

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "leafPack", ignore = true)
    @Mapping(target = "status", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    Leaf toLeaf(CreateLeafRequestDTO requestDTO);

    @Mapping(target = "leafPackId", source = "leafPack.id")
    LeafResponseDTO toResponse(Leaf leaf);
}
